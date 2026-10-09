package com.haf1.racecontrol.desktop;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * 内置 Web 服务器：把界面和状态发给本机、局域网、以及 iPhone。
 *
 * ## 为什么用 com.sun.net.httpserver
 * 它是 JDK 自带的（Java 6 起，Java 9 起是 jdk.httpserver 模块），
 * **零第三方依赖** —— 这个项目的卖点之一就是"不用装任何东西"。
 *
 * ## 为什么用 SSE 而不是 WebSocket
 * 数据是**单向**的：服务器推状态，浏览器只读。SSE 就是为这个场景造的，
 * 而且是 HTTP 原生（不需要握手、不需要在 Java 侧手写 RFC6455 帧）。
 * 项目里已经有 WsFrame.java 会帧编解码，但那是客户端连 F1 用的，
 * 拿来做服务端是给自己找麻烦。
 *
 * ## 界面资源从哪来
 * 先找磁盘上的 web/ 目录（开发时改完刷新就见效），找不到再从 jar 里取
 * （发布时一个 jar 自带界面，不会缺文件）。
 */
public final class WebServer {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** 可单独拉出的看板。顺序就是界面上的顺序。 */
    public static final String[][] BOARDS = {
            {"flags", "顶部旗语栏"},
            // ring 就是赛道图（一圈区段），和安卓的叫法一致；track 是文字汇总，
            // 所以叫"赛道状态" —— 两块板不能同名。
            {"ring", "赛道图"},
            {"track", "赛道状态"},
            {"tyres", "轮胎进站"},
            {"timing", "成绩榜"},
            {"weather", "天气"},
            {"fastest", "最快圈"},
            {"session", "环节"},
            {"messages", "赛事通报"},
    };

    private final AppCore core;
    private final Path webDir;
    private final int port;
    private final Set<OutputStream> clients =
            Collections.newSetFromMap(new ConcurrentHashMap<OutputStream, Boolean>());
    private final long[] lastPushed = new long[]{-1L};

    // ---- 「点面板弹出独立小窗」需要的上下文（由 DesktopMain 注入）
    private String boardExe;
    private String browser;
    private int boardW = 620;
    private int boardH = 420;

    /**
     * 已经弹出的看板：id → 窗口进程。
     *
     * ★ 必须留在服务器侧，不能只让前端记状态：窗口是用户直接用 X 关掉的，
     *   前端根本收不到通知。所以"弹没弹出"永远以进程是否还活着为准
     *   （见 {@link #isPopped}），前端的状态是问出来的，不是猜出来的。
     */
    private final Map<String, Process> popped =
            new ConcurrentHashMap<String, Process>();

    private HttpServer server;
    private volatile boolean running;
    private int boundPort;
    /** 「第一个页面来自哪」只记一次（见 /api/boards）。 */
    private volatile boolean loggedCaller;

    public WebServer(AppCore core, Path webDir, int port) {
        this.core = core;
        this.webDir = webDir;
        this.port = port;
    }

    /** 告诉服务器用哪个程序开窗口（这样才能响应网页上的"弹出"请求）。 */
    public void setWindowContext(String boardExe, String browser, int w, int h) {
        this.boardExe = boardExe;
        this.browser = browser;
        if (w > 0) {
            this.boardW = w;
        }
        if (h > 0) {
            this.boardH = h;
        }
    }

    private boolean isPopped(String id) {
        Process p = popped.get(id);
        if (p == null) {
            return false;
        }
        if (Boards.isAlive(p)) {
            return true;
        }
        popped.remove(id);   // 进程没了（用户关掉了），顺手清掉
        return false;
    }

    /**
     * 收回所有已弹出的看板窗口。
     *
     * 界面上那个「收回全部」按钮走这里。别去动 Boards.OPENED ——
     * 那张表还包括主界面窗口，用户按「收回全部」显然不是想把自己所在的
     * 这个窗口关掉。
     */
    private int closeAllPopped() {
        int n = 0;
        List<String> keys = new ArrayList<String>(popped.keySet());
        for (int i = 0; i < keys.size(); i++) {
            String k = keys.get(i);
            Process p = popped.remove(k);
            if (Boards.isAlive(p)) {
                Boards.close(p);
                n++;
            }
        }
        return n;
    }

    public int port() {
        return boundPort;
    }

    public int clientCount() {
        return clients.size();
    }

    // ------------------------------------------------------------------

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 64);

        ThreadFactory tf = new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "http-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        // SSE 会长时间占住线程，所以不能给固定大小的小池子。
        server.setExecutor(Executors.newCachedThreadPool(tf));

        server.createContext("/api/state", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                sendJson(ex, 200, core.stateJson());
            }
        });
        server.createContext("/api/full", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                sendJson(ex, 200, core.fullJson());
            }
        });
        server.createContext("/api/boards", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                Json.Arr a = new Json.Arr();
                for (int i = 0; i < BOARDS.length; i++) {
                    Json.Obj o = new Json.Obj();
                    o.put("id", BOARDS[i][0]);
                    o.put("title", BOARDS[i][1]);
                    o.put("popped", isPopped(BOARDS[i][0]));
                    a.raw(o.done());
                }
                Json.Obj o = new Json.Obj();
                o.raw("boards", a.done());
                o.put("port", boundPort);
                o.put("canNative", wantsNative(ex));
                // 只记第一次：这条日志回答的是"这个页面到底跑在浏览器里，还是跑在
                // 我们自己的窗口里"。用户报"点弹出没反应/弹出形态不对"时，第一件
                // 事就是看这里 —— 光看来源地址（都是回环）分不出来。
                if (!loggedCaller) {
                    loggedCaller = true;
                    System.out.println("[web] 第一个页面连接来自：" + (fromThisMachine(ex)
                            ? (fromShell(ex) ? "本机 · 我们的窗口" : "本机 · 浏览器")
                            : "局域网设备"));
                }
                sendJson(ex, 200, o.done());
            }
        });

        /*
         * 点一下面板 → 弹出 / 收回独立小窗。
         *
         * 这是"小窗应该能自由选择固定在看板上或独立分出来"的服务端一半。
         * 判两件事，别混在一起：
         *
         *   1) **能不能**开原生窗口 —— 只有回环地址可以。否则局域网里任何一台
         *      设备都能让这台电脑弹窗，那是个能被滥用的洞，不是功能。
         *   2) **想不想要**原生窗口 —— 只有跑在我们自己窗口里的页面才要。
         *      同样是回环地址，本机浏览器里点「弹出」应该是开一个**网页标签页**
         *      （用户明确要求的："网页版弹出窗口也应该是网页"），而在我们的
         *      原生窗口里点才是再开一个原生小窗（像微信那样）。
         *      两条都是回环，只能靠 UA 上的标记（见 Boards/native 的 ShellMarker）
         *      区分。
         *
         * 两者都满足才开原生窗口；否则如实返回 mode=tab，由前端 window.open 兜。
         * 猜错的后果是"点了没反应"或"开错形态"，两种都很难受。
         */
        server.createContext("/api/popout", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                String path = ex.getRequestURI().getPath();
                String id = path.startsWith("/api/popout/")
                        ? path.substring("/api/popout/".length()) : "";

                // 「收回全部」：一次把所有弹出去的小窗关掉。
                // 必须放在 isBoard 判断之前 —— "all" 不是一个看板 id。
                if ("all".equals(id)) {
                    // 和"弹出"同一条规矩：只有跑在我们自己窗口里、且在这台机器上
                    // 的页面才能动这些窗口。
                    // 手机上我们的窗口不在那台设备上；本机浏览器里也不该有这条路
                    // （网页版点「弹出」开的是标签页，那些标签页服务器也关不掉，
                    //   所以浏览器里根本没有"收回全部"这个按钮）。
                    // 两种都如实说，不假装成功。
                    if (!wantsNative(ex)) {
                        Json.Obj o = new Json.Obj();
                        o.put("ok", false);
                        o.put("mode", "tab");
                        o.put("error", fromThisMachine(ex)
                                ? "网页版里收不了本机窗口，请在桌面客户端里点"
                                : "只有本机能收回窗口");
                        sendJson(ex, 200, o.done());
                        return;
                    }
                    int n = closeAllPopped();
                    Json.Obj o = new Json.Obj();
                    o.put("ok", true);
                    o.put("closed", n);
                    o.put("popped", false);
                    sendJson(ex, 200, o.done());
                    return;
                }

                if (!isBoard(id)) {
                    error(ex, 404, "没有这个看板：" + id);
                    return;
                }
                String title = boardTitle(id);
                Json.Obj o = new Json.Obj();
                o.put("id", id);
                o.put("title", title);

                if (isPopped(id)) {
                    // 已经弹出 → 这次点击表示收回
                    Boards.close(popped.remove(id));
                    o.put("popped", false);
                    o.put("closed", true);
                    sendJson(ex, 200, o.done());
                    return;
                }

                if (!fromThisMachine(ex)) {
                    // 手机 / 局域网设备：开不了原生窗口，交给前端开新标签页
                    o.put("popped", false);
                    o.put("mode", "tab");
                    o.put("url", "/board/" + id);
                    sendJson(ex, 200, o.done());
                    return;
                }

                if (!fromShell(ex)) {
                    // 本机、但是在**浏览器**里看的 —— 那就是"网页版"，
                    // 弹出也应该是网页。给个新标签页地址，不开原生窗口。
                    o.put("popped", false);
                    o.put("mode", "tab");
                    o.put("url", "/board/" + id);
                    o.put("reason", "浏览器里用网页版：新标签页打开");
                    sendJson(ex, 200, o.done());
                    return;
                }

                if (boardExe == null && browser == null) {
                    // 本机也没有可用的窗口程序 —— 退回让浏览器开新标签页，
                    // 而不是假装弹了个窗口
                    o.put("popped", false);
                    o.put("mode", "tab");
                    o.put("url", "/board/" + id);
                    o.put("reason", "没有 " + Boards.BOARD_EXE
                            + "，也没找到 Edge/Chrome");
                    sendJson(ex, 200, o.done());
                    return;
                }

                try {
                    // 位置：摆过的回到原位（用户摆一次就够了），没摆过的才给个网格槽位。
                    // ★ 不能无条件传槽位 —— 传了坐标，窗口那边就当成"用户显式指定"，
                    //   记住的位置永远用不上。
                    int[] pos = Boards.hasRememberedBounds(id)
                            ? new int[]{-1, -1}
                            : Boards.suggestedSlot(popped.size(), boardW, boardH);
                    Boards.Opened op = Boards.open(boardExe, browser, id, title,
                            "http://127.0.0.1:" + boundPort + "/board/" + id,
                            boardW, boardH, pos[0], pos[1]);
                    if (op.process != null) {
                        popped.put(id, op.process);
                    }
                    o.put("popped", op.process != null);
                    o.put("mode", op.mode);
                    o.put("how", op.describe());
                    sendJson(ex, 200, o.done());
                } catch (IOException e) {
                    error(ex, 500, "开窗口失败：" + e.getMessage());
                }
            }
        });
        server.createContext("/api/health", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                Json.Obj o = new Json.Obj();
                o.put("ok", true);
                o.put("open", core.isLive());
                o.put("listenerOpen", core.isOpen());
                o.put("updates", core.updates());
                o.put("clients", clients.size());
                o.put("error", core.error());
                o.put("port", boundPort);
                sendJson(ex, 200, o.done());
            }
        });
        server.createContext("/api/events", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                sse(ex);
            }
        });
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                staticOrPage(ex);
            }
        });

        server.start();
        boundPort = server.getAddress().getPort();
        running = true;
        Thread t = new Thread(this::broadcastLoop, "sse-broadcast");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        if (server != null) {
            server.stop(0);
        }
    }

    // ------------------------------------------------------------------
    // SSE
    // ------------------------------------------------------------------

    private void sse(HttpExchange ex) throws IOException {
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", "text/event-stream; charset=utf-8");
        h.set("Cache-Control", "no-cache, no-transform");
        h.set("Connection", "keep-alive");
        // 让 nginx/缓冲代理别攒着不发（本机直连用不到，但加上无害）
        h.set("X-Accel-Buffering", "no");
        ex.sendResponseHeaders(200, 0);

        OutputStream os = ex.getResponseBody();
        clients.add(os);
        try {
            // 先补一帧，别让客户端干等到下一次更新
            write(os, "event: state\n");
            write(os, "data: " + core.stateJson() + "\n\n");
            // 然后挂着，等广播线程
            long deadline = System.currentTimeMillis() + 3600_000L;
            while (running && System.currentTimeMillis() < deadline) {
                Thread.sleep(1000);
                write(os, ": ping\n\n");
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // 浏览器关了页面 —— 正常
        } finally {
            clients.remove(os);
            try {
                os.close();
            } catch (IOException ignored) {
                // 已经断了
            }
            ex.close();
        }
    }

    private void broadcastLoop() {
        while (running) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            long now = core.updates();
            if (now == lastPushed[0]) {
                continue;
            }
            lastPushed[0] = now;
            String payload = "event: state\ndata: " + core.stateJson() + "\n\n";
            for (OutputStream os : new ArrayList<OutputStream>(clients)) {
                try {
                    write(os, payload);
                } catch (IOException e) {
                    clients.remove(os);
                }
            }
        }
    }

    private static void write(OutputStream os, String s) throws IOException {
        os.write(s.getBytes(UTF8));
        os.flush();
    }

    // ------------------------------------------------------------------
    // 静态资源 / 页面
    // ------------------------------------------------------------------

    private void staticOrPage(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path == null) {
            path = "/";
        }
        if (path.contains("..")) {
            sendText(ex, 400, "坏路径");
            return;
        }

        // /board/<id> -> board.html（前端自己按 id 渲染那一块）
        if (path.startsWith("/board/")) {
            byte[] page = asset("board.html");
            if (page == null) {
                sendText(ex, 500, "缺 board.html");
                return;
            }
            sendBytes(ex, 200, "text/html; charset=utf-8", page);
            return;
        }

        String rel = path.equals("/") ? "index.html" : path.substring(1);
        byte[] data = asset(rel);
        if (data == null) {
            sendText(ex, 404, "没有 " + path);
            return;
        }
        sendBytes(ex, 200, mime(rel), data);
    }

    /** 先磁盘后 jar。 */
    private byte[] asset(String rel) {
        if (webDir != null) {
            Path p = webDir.resolve(rel);
            if (Files.isRegularFile(p)) {
                try {
                    return Files.readAllBytes(p);
                } catch (IOException e) {
                    // 落到 classpath 再试
                }
            }
        }
        InputStream in = WebServer.class.getResourceAsStream("/web/" + rel);
        if (in == null) {
            return null;
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 14);
            byte[] buf = new byte[1 << 14];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (IOException e) {
            return null;
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // 无所谓
            }
        }
    }

    private static String mime(String rel) {
        String r = rel.toLowerCase();
        if (r.endsWith(".html") || r.endsWith(".htm")) {
            return "text/html; charset=utf-8";
        }
        if (r.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (r.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (r.endsWith(".json")) {
            return "application/json; charset=utf-8";
        }
        if (r.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (r.endsWith(".png")) {
            return "image/png";
        }
        if (r.endsWith(".woff2")) {
            return "font/woff2";
        }
        return "application/octet-stream";
    }

    // ------------------------------------------------------------------

    private static void sendJson(HttpExchange ex, int code, String json)
            throws IOException {
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        sendBytes(ex, code, "application/json; charset=utf-8",
                json.getBytes(UTF8));
    }

    private static void sendText(HttpExchange ex, int code, String s)
            throws IOException {
        sendBytes(ex, code, "text/plain; charset=utf-8", s.getBytes(UTF8));
    }

    private static void sendBytes(HttpExchange ex, int code, String ctype,
                                  byte[] data) throws IOException {
        ex.getResponseHeaders().set("Content-Type", ctype);
        ex.sendResponseHeaders(code, data.length);
        OutputStream os = ex.getResponseBody();
        os.write(data);
        os.close();
    }

    // ------------------------------------------------------------------
    // 找 web/ 目录
    // ------------------------------------------------------------------

    /**
     * 定位 web/ 资源目录。
     *
     * 顺序：显式 --web > jar/exe 同级的 web/ > 当前工作目录的 web/。
     * 都找不到就返回 null，此时走 classpath（发布形态）。
     */
    public static Path findWebDir(String explicit) {
        List<Path> cands = new ArrayList<Path>();
        if (explicit != null && explicit.length() > 0) {
            cands.add(Paths.get(explicit));
        }
        try {
            Path self = Paths.get(WebServer.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path base = Files.isDirectory(self) ? self : self.getParent();
            if (base != null) {
                cands.add(base.resolve("web"));
                if (base.getParent() != null) {
                    cands.add(base.getParent().resolve("web"));
                }
            }
        } catch (Exception ignored) {
            // 拿不到就算
        }
        cands.add(Paths.get("web").toAbsolutePath());
        for (Path p : cands) {
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        return null;
    }

    static List<String> boardIds() {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < BOARDS.length; i++) {
            out.add(BOARDS[i][0]);
        }
        return Arrays.asList(out.toArray(new String[0]));
    }

    /** 是不是一个存在的看板 id。 */
    static boolean isBoard(String id) {
        if (id == null) {
            return false;
        }
        for (int i = 0; i < BOARDS.length; i++) {
            if (BOARDS[i][0].equals(id)) {
                return true;
            }
        }
        return false;
    }

    /** 看板的显示名。认不出来就回显 id，不要吞掉。 */
    static String boardTitle(String id) {
        for (int i = 0; i < BOARDS.length; i++) {
            if (BOARDS[i][0].equals(id)) {
                return BOARDS[i][1];
            }
        }
        return id;
    }

    /**
     * 请求是不是来自这台电脑本身。
     *
     * 用来决定「点面板弹出小窗」是开原生窗口还是让浏览器开新标签页，
     * 同时**也是一道安全边界**：只有本机才能让这台电脑弹窗，
     * 否则局域网里任何人都能让你的电脑弹出窗口。
     */
    private static boolean fromThisMachine(HttpExchange ex) {
        InetSocketAddress ra = ex.getRemoteAddress();
        if (ra == null || ra.getAddress() == null) {
            return false;
        }
        return ra.getAddress().isLoopbackAddress();
    }

    /**
     * 这个页面是不是跑在**我们自己的原生窗口**里（而不是浏览器里）。
     *
     * 靠 WebView2 在 UA 尾巴上盖的标记判断（native/BoardWindow/Program.cs 的
     * ShellMarker，改一处必须改两处）。
     *
     * 为什么需要这个：来源地址分不出"我们的窗口"和"本机浏览器" —— 两者都是
     * 回环地址。而这两处用户的期望正好相反：我们的窗口里点「弹出」要再开一个
     * 原生小窗（像微信那样），浏览器里点「弹出」要开一个**网页标签页**
     * （用户明确要求："网页版弹出窗口也应该是网页"）。
     *
     * ★ 它只回答"想不想要原生窗口"，不回答"能不能"。能不能仍然只看来源地址
     *   （fromThisMachine）—— 那是安全边界，不能靠一个客户端可以随便改的
     *   UA 字符串来决定。
     */
    private static boolean fromShell(HttpExchange ex) {
        String ua = ex.getRequestHeaders().getFirst("User-Agent");
        return ua != null && ua.indexOf(SHELL_MARKER) >= 0;
    }

    /** 能不能 + 想不想要，两个都满足才是"该开原生窗口"。 */
    private static boolean wantsNative(HttpExchange ex) {
        return fromThisMachine(ex) && fromShell(ex);
    }

    /** 和 native/BoardWindow/Program.cs 的 ShellMarker 必须一模一样。 */
    static final String SHELL_MARKER = "F1RaceControlShell/1";

    /** 出错也要返回 JSON —— 前端是按 JSON 解析的，回一段 HTML 会让它二次出错。 */
    private static void error(HttpExchange ex, int code, String msg) throws IOException {
        Json.Obj o = new Json.Obj();
        o.put("ok", false);
        o.put("error", msg);
        sendJson(ex, code, o.done());
    }
}