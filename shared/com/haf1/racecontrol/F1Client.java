package com.haf1.racecontrol;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * F1 官方实时流的客户端 —— A 方案的**唯一数据来源**，不带任何令牌。
 *
 * ## 端点（全部实测过，不需要 F1TV 订阅）
 *
 *     POST https://livetiming.formula1.com/signalrcore/negotiate?negotiateVersion=1
 *          -> {"connectionToken":"..."}
 *     GET  wss://livetiming.formula1.com/signalrcore?id=<connectionToken>
 *          -> 101 Switching Protocols
 *     发   {"protocol":"json","version":1} + 0x1e
 *     发   {"type":1,"target":"Subscribe","arguments":[[流名...]],"invocationId":"0"} + 0x1e
 *
 * `RaceControlMessages` / `TrackStatus` / `TimingData` / `TimingAppData` /
 * `DriverList` 这些都在官方**公开**流里；需要 F1TV 的只有 `CarData.z`、
 * `Position.z`、`TeamRadio` 那几个，本 App 一个都不用。
 *
 * ## 三个必须处理的细节（全是实测踩出来的）
 *
 * 1. **令牌是一次性的**。同一个 connectionToken 用第二次直接 404，
 *    所以每次重连都必须重新 negotiate —— 不能在重连时复用旧令牌。
 * 2. **大消息会分片**。初始快照几十 KB，服务端按 FIN/续帧拆开，
 *    必须用 {@link WsFrame#readMessage} 重组，否则只会拿到半截 JSON。
 * 3. **心跳要回**。收到 `{"type":6}` 必须回一条 `{"type":6}`，
 *    否则服务端会断开连接。
 *
 * ## 与 HA 那套的区别
 * `HaWebSocket` 走的是 HA 自己的 auth/subscribe 协议；F1 是 SignalR，
 * 只有"TCP + HTTP Upgrade + RFC6455 帧"这三层相同，所以帧编解码复用
 * {@link WsFrame}，其余另写。记录之间用 0x1e（RS）分隔。
 */
public class F1Client implements FeedSource {

    public static final String HOST = "livetiming.formula1.com";
    public static final String NEGOTIATE_URL =
            "https://" + HOST + "/signalrcore/negotiate?negotiateVersion=1";
    public static final String WS_PATH = "/signalrcore";
    /** SignalR 的记录分隔符。 */
    public static final char RS = '\u001e';

    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 90000;
    private static final int MAX_IDLE_ROUNDS = 2;
    /** 重连退避上限。 */
    private static final int MAX_BACKOFF_MS = 60000;

    public interface Listener {
        /** 握手 + 订阅成功（每次重连都会再报一次）。 */
        void onOpen();
        /** 状态有变化。 */
        void onFeed(F1Feed feed);
        /** 出错（之后会自动重连）。 */
        void onError(String message);
        /** 连接断开（无论正常还是异常）。 */
        void onClose();
    }

    /** 供调用方复用：直接喂给 {@link F1Feed#onRecord}。 */
    private final F1Feed feed;
    private final Listener listener;

    private volatile boolean closed = false;
    /**
     * 当前连接。
     *
     * ★ 包内可见（而不是 private）**只**为了一件事：单测要塞一个假 Socket
     *   进来，验证 {@link #stop()} 不会在调用线程上 close()。
     *   见 stop() 的说明 —— 这种崩法在桌面上不会自己暴露，必须有测试钉住。
     */
    volatile Socket socket;
    /** negotiate 返回的亲和性 Cookie（AWSALB / AWSALBTG 等）。握手时要带上。 */
    private String affinityCookies = "";
    private InputStream in;
    private OutputStream out;
    private String lastError = "";

    public F1Client(Listener listener) {
        this.feed = new F1Feed();
        this.listener = listener;
        // ★ 这里**不**给 feed 装监听器：由调用方自己装。
        //   主界面要在**每条新消息**上做告警，而不是每帧刷一次。
        //   （原来装了个空的，看着像已经处理了，其实是死的。）
    }

    public F1Feed feed() {
        return feed;
    }

    public String lastError() {
        return lastError;
    }

    /**
     * 从外部线程调用以中止连接。**可以随便从主线程调。**
     *
     * ★ close() 必须甩到别的线程去做，这是 v3.1.1 修的那个崩溃。
     *
     *   Android 的 StrictMode 把「主线程上做网络操作」直接判成
     *   NetworkOnMainThreadException，而 OpenSSLSocketImpl.close() 也算一个
     *   —— 它内部要 shutdownAndFreeSslNative()，属于网络动作。
     *
     *   于是「在设置页换数据源 -> 返回主界面 -> onResume 里停掉旧连接」
     *   这条路上，close() 在 resume 阶段抛异常，被包成
     *   `Unable to resume activity ... NetworkOnMainThreadException`，
     *   整个界面起不来。栈里只看到 restartClient -> stop，很容易误判成
     *   "回放功能坏了"，其实是关连接的方式错了。
     *
     *   onDestroy() 里那句 stop() 一直有同样的隐患（退出 App 时），
     *   只是那时 Activity 反正要没了，不容易被发现。
     *
     * 关连接**不能**省掉：读循环正阻塞在 read 上，不关就退不出来（要等到
     * 90 秒读超时）。所以是"换个线程关"，不是"不关"。
     */
    public void stop() {
        closed = true;
        Socket s = socket;
        socket = null;              // 重复调用时别关第二次
        if (s != null) {
            closeSocketOffMainThread(s);
        }
    }

    /**
     * 在一个短命线程里 close()，这样调用方（可能是主线程）立刻返回。
     *
     * 包内可见是为了单测：断言 close 确实发生在**别的**线程上。
     */
    static void closeSocketOffMainThread(final Socket s) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    s.close();
                } catch (Throwable ignored) {
                    // 关不掉也不影响：closed 已置位，读循环自己会退出
                }
            }
        }, "f1-close");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 阻塞运行，断线自动重连，直到 {@link #stop()}。
     *
     * 请放到独立线程里跑：它会一直循环。
     */
    public void runForever() {
        int backoff = 1000;
        while (!closed) {
            boolean ok = false;
            try {
                ok = runOnce();
            } catch (Throwable t) {
                lastError = describe(t);
            } finally {
                if (!closed && listener != null) {
                    listener.onClose();
                }
            }
            if (closed) {
                break;
            }
            if (ok) {
                backoff = 1000;          // 成功连过就重置退避
            }
            sleep(backoff);
            backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
        }
    }

    private void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closed = true;
        }
    }

    /** 连一次；返回 true 表示成功握手并订阅过。 */
    private boolean runOnce() throws IOException {
        // ★ 每次都重新 negotiate：令牌一次性，复用必然 404。
        String token = negotiate();
        if (token == null || token.length() == 0) {
            throw new IOException("negotiate 没有返回 connectionToken");
        }

        // ★ Android 上必须用「带主机名」的重载来建 SSLSocket，否则**不发 SNI**。
        //
        //   原来的写法是 createSocket()（无参）+ connect()。在桌面 JSSE 上这样
        //   能发 SNI，所以测试全绿；但在 Android 上不保证，而
        //   livetiming.formula1.com 走 CloudFront —— 没有 SNI 时 TLS 可能照样
        //   握手成功（拿到默认证书），请求却被路由到错误的站点，HTTP 层返回 4xx。
        //
        //   症状正是用户遇到的那个组合：**negotiate 成功、握手失败**。
        //   因为 negotiate 走 HttpURLConnection，它自己会发 SNI。
        //
        //   先建一条普通 TCP（这样才设得上连接超时），再包成带主机名的 SSLSocket。
        Socket plain = new Socket();
        plain.connect(new InetSocketAddress(HOST, 443), CONNECT_TIMEOUT_MS);
        plain.setSoTimeout(READ_TIMEOUT_MS);
        plain.setTcpNoDelay(true);
        // getDefault() 的**声明返回类型**是 javax.net.SocketFactory，
        // 在它上面看不到 SSLSocketFactory 的那个带主机名的重载，必须先转类型。
        // （变量不能叫 f —— 下面读帧那段的 `WsFrame f` 已经占了。）
        SSLSocketFactory sslFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket ssl = (SSLSocket) sslFactory.createSocket(plain, HOST, 443, true);
        ssl.startHandshake();          // 显式握手：失败早点冒出来，错误也更清楚
        Socket s = ssl;
        socket = s;
        // ★ 竞态：stop() 可能刚好在这行之前跑完 —— 那时 socket 还是 null，
        //   它什么也关不了，这条刚建好的连接就会一直挂到 90 秒读超时。
        //   这里补一刀。当前是读线程，同步 close 没问题。
        if (closed) {
            socket = null;
            try {
                s.close();
            } catch (IOException ignored) {
                // 反正要走了
            }
            throw new IOException("已停止（连接刚建立就被关掉）");
        }
        in = new BufferedInputStream(s.getInputStream());
        out = new BufferedOutputStream(s.getOutputStream());

        handshake(token);
        send(handshakeMsg());
        send(subscribeMsg());

        boolean opened = false;
        int idleRounds = 0;
        String pending = "";
        while (!closed) {
            WsFrame f;
            try {
                f = WsFrame.readMessage(in);      // 自动重组分片
                idleRounds = 0;
            } catch (SocketTimeoutException te) {
                idleRounds++;
                if (idleRounds >= MAX_IDLE_ROUNDS) {
                    throw new IOException("空闲超时（已发心跳但无响应）");
                }
                sendControl(WsFrame.OP_PING, new byte[0]);
                continue;
            }
            if (f.opcode == WsFrame.OP_PING) {
                sendControl(WsFrame.OP_PONG, f.payload);
                continue;
            }
            if (f.opcode == WsFrame.OP_PONG) {
                continue;
            }
            if (f.opcode == WsFrame.OP_CLOSE) {
                throw new IOException("服务端关闭了连接");
            }
            if (f.opcode != WsFrame.OP_TEXT) {
                continue;
            }
            pending += new String(f.payload, "UTF-8");
            String[] records = pending.split(String.valueOf(RS), -1);
            // 最后一段可能是不完整的（下一次才补齐），留着
            pending = records[records.length - 1];
            boolean changed = false;
            for (int i = 0; i < records.length - 1; i++) {
                String rec = records[i].trim();
                if (rec.length() == 0) {
                    continue;
                }
                JSONObject o;
                try {
                    o = new JSONObject(rec);
                } catch (Exception e) {
                    continue;                     // 不是 JSON 就跳过，不断流
                }
                int type = o.optInt("type", -1);
                if (type == 6) {
                    send(pingReplyMsg());          // ★ 必须回，否则会被断开
                    continue;
                }
                if (type == 7) {
                    throw new IOException("服务端要求关闭：" + rec);
                }
                if (type == 3 && !opened) {
                    opened = true;
                    if (listener != null) {
                        listener.onOpen();
                    }
                }
                changed |= feed.onRecord(o);
            }
            if (changed && listener != null) {
                listener.onFeed(feed);
            }
        }
        closeQuietly();
        return opened;
    }

    // ------------------------------------------------------------------
    // 协议消息
    // ------------------------------------------------------------------

    static String handshakeMsg() {
        return "{\"protocol\":\"json\",\"version\":1}" + RS;
    }

    static String pingReplyMsg() {
        return "{\"type\":6}" + RS;
    }

    static String subscribeMsg() {
        StringBuilder b = new StringBuilder();
        b.append("{\"type\":1,\"target\":\"Subscribe\",\"arguments\":[[");
        for (int i = 0; i < F1Feed.STREAMS.length; i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append('"').append(F1Feed.STREAMS[i]).append('"');
        }
        b.append("]],\"invocationId\":\"0\"}").append(RS);
        return b.toString();
    }

    /**
     * 测试官方流是否可达（只做一次 negotiate，不建长连接）。
     *
     * 给设置页的“测试连接”用。没有参数、不需要任何凭据。
     */
    public static String testConnectivity() throws IOException {
        F1Client c = new F1Client(null);
        String token = c.negotiate();
        if (token == null || token.length() == 0) {
            throw new IOException("negotiate 没有返回 connectionToken");
        }
        return "官方流可达：已拿到连接令牌（" + token.length() + " 字符）";
    }

    /**
     * 拿一次性连接令牌。
     *
     * 只发一个 POST、空 body。**不需要任何鉴权头** —— 公开流就是这样。
     * 实测 OPTIONS 会返回 405 并且带一个 AWSALBCORS 亲和性 cookie，
     * 但那个 cookie 也**不是必需的**（去掉照样 101），所以这里不折腾它。
     */
    String negotiate() throws IOException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(NEGOTIATE_URL).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(CONNECT_TIMEOUT_MS);
            c.setRequestProperty("User-Agent", "F1-RaceControl");
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(0);
            c.getOutputStream().close();
            int code = c.getResponseCode();
            if (code != 200) {
                throw new IOException("negotiate HTTP " + code);
            }
            // ★ 把 Set-Cookie 里的 name=value 收下来。
            //   负载均衡的亲和性 cookie：不带的话，
            //   WebSocket 升级可能落到另一个后端，
            //   那边认不出这个一次性令牌 -> 4xx。
            StringBuilder ck = new StringBuilder();
            Map<String, List<String>> hdrs = c.getHeaderFields();
            for (Map.Entry<String, List<String>> en : hdrs.entrySet()) {
                if (en.getKey() == null
                        || !"set-cookie".equalsIgnoreCase(en.getKey())) {
                    continue;
                }
                List<String> vs = en.getValue();
                if (vs == null) {
                    continue;
                }
                for (int i = 0; i < vs.size(); i++) {
                    String v = vs.get(i);
                    if (v == null) {
                        continue;
                    }
                    int semi = v.indexOf(';');
                    String pair = (semi > 0 ? v.substring(0, semi) : v).trim();
                    if (pair.length() == 0) {
                        continue;
                    }
                    if (ck.length() > 0) {
                        ck.append("; ");
                    }
                    ck.append(pair);
                }
            }
            affinityCookies = ck.toString();
            InputStream is = c.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            is.close();
            JSONObject o = new JSONObject(new String(bos.toByteArray(), "UTF-8"));
            String t = o.optString("connectionToken", "");
            if (t.length() == 0) {
                t = o.optString("ConnectionToken", "");
            }
            return t;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("negotiate 失败：" + e.getMessage());
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    // ------------------------------------------------------------------
    // 底层
    // ------------------------------------------------------------------

    private void send(String text) throws IOException {
        byte[] payload = text.getBytes("UTF-8");
        out.write(WsFrame.encode(WsFrame.OP_TEXT, payload));
        out.flush();
    }

    private void sendControl(int opcode, byte[] payload) {
        try {
            out.write(WsFrame.encode(opcode, payload));
            out.flush();
        } catch (IOException ignored) {
            // 下一轮 read 会抛出来，交给重连处理
        }
    }

    private void handshake(String token) throws IOException {
        byte[] nonce = new byte[16];
        new SecureRandom().nextBytes(nonce);
        // ★ 必须是 16 字节的 base64。早先用过 hex，服务端直接 400。
        String key = Base64.encodeToString(nonce, Base64.NO_WRAP);

        String path = WS_PATH + "?id=" + URLEncoder.encode(token, "UTF-8");
        // Origin 不是必须的（实测任何 Origin 甚至不带都能 101），但真实客户端
        // 都会带，带上更不容易被边缘节点当成异常流量。
        String req = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + HOST + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "Origin: https://www.formula1.com\r\n"
                + (affinityCookies.length() > 0
                        ? "Cookie: " + affinityCookies + "\r\n" : "")
                + "User-Agent: F1-RaceControl\r\n"
                + "\r\n";
        out.write(req.getBytes("UTF-8"));
        out.flush();

        String head = readHttpHead();
        if (head == null || head.indexOf("101") < 0) {
            // 把**完整响应头**带上。原来只留第一行，信息不够 ——
            // 真出问题时看不到 Server / x-amz-cf-id 这类线索，只能靠猜。
            String detail = head == null ? "(没有响应)"
                    : head.replace("\r\n", "  ").trim();
            if (detail.length() > 220) {
                detail = detail.substring(0, 220) + "…";
            }
            throw new IOException("WebSocket 握手失败：" + detail);
        }
    }

    /** 读 HTTP 响应头（到空行为止），返回整段文本。 */
    private String readHttpHead() throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            int b = in.read();
            if (b < 0) {
                break;
            }
            bos.write(b);
            if ((state == 0 || state == 2) && b == '\r') {
                state++;
            } else if ((state == 1 || state == 3) && b == '\n') {
                state++;
            } else {
                state = 0;
            }
            if (bos.size() > 16384) {
                throw new IOException("响应头过长");
            }
        }
        return new String(bos.toByteArray(), "UTF-8");
    }

    private void closeQuietly() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
            // ignore
        }
        socket = null;
    }

    private static String describe(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.length() == 0) {
            m = t.getClass().getSimpleName();
        }
        return m;
    }
}
