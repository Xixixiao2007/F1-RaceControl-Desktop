package com.haf1.racecontrol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 回放：把官方归档压成的"回放包"喂给 {@link F1Feed}。
 *
 * <h3>为什么要这个东西</h3>
 * 真机上的崩溃只在真实比赛数据下出现，而一年只有二十几场 —— 崩了就得再等一周，
 * 还不能复现、不能回放。这个类把"等下一场比赛"换成"随时重放昨天那场"。
 *
 * <h3>为什么是"假数据但真格式"</h3>
 * 包里的每一行就是线上 WebSocket 收到的一个文本帧，
 * 记录之间同样用 {@code 0x1e} 分隔，快照同样是 {@code type:3}、
 * 增量同样是 {@code type:1}。走的解析路径和 {@link F1Client} 一模一样 ——
 * 差别只在记录从哪儿来。
 *
 * <h3>时间戳要改写（否则提醒一次都不会响）</h3>
 * 主界面的告警门限是"消息不超过 3 分钟"，而归档里 10 月 4 日的消息到现在早就是
 * 几小时前了。所以增量里的 {@code RaceControlMessages} 会被改成**当前时刻** ——
 * 这正是线上发生的事（新消息的 Utc 就是收到它的时刻）。
 * 快照里那批历史消息**不改**：它们在线上本来就是旧的，改了反而会一开机就炸二十几条全屏告警。
 *
 * <h3>这个类不依赖任何 Android API</h3>
 * 拿包的方式由调用方用 {@link Opener} 给（Android 给 assets，桌面测试给文件），
 * 所以单元测试可以直接拿真包跑一遍完整管线。
 */
public class ReplayClient implements FeedSource {

    /** 怎么拿到回放包。 */
    public interface Opener {
        InputStream open() throws IOException;
    }

    /**
     * {@code .rclog} 第一行的魔数。App 靠它认出"这是回放文件"。
     *
     * 第一行是**可选**的 —— 没有它也能放，只是界面上显示不出"这是什么比赛"。
     */
    public static final String MAGIC = "RCLOG1";

    /**
     * {@code .rclog} 自带的元信息（第一行那个 JSON）。
     *
     * 存在的意义：设置页让用户**挑一个文件**，挑完总得告诉他"这是什么"。
     * 没有它的话就只能显示个文件名，而文件名是用户自己起的。
     */
    public static final class Meta {
        public final String name;
        public final String date;
        public final String session;
        public final long snapshotMs;
        public final long durationMs;
        public final int snapshotMsgs;
        public final int frames;
        public final String note;

        Meta(String name, String date, String session, long snapshotMs,
             long durationMs, int snapshotMsgs, int frames, String note) {
            this.name = name;
            this.date = date;
            this.session = session;
            this.snapshotMs = snapshotMs;
            this.durationMs = durationMs;
            this.snapshotMsgs = snapshotMsgs;
            this.frames = frames;
            this.note = note;
        }

        /** "2026 巴林站 正赛 · 中途接入（2026-10-04）"。 */
        public String label() {
            if (name.length() == 0) {
                return "回放文件";
            }
            return name + (date.length() > 0 ? "（" + date + "）" : "");
        }

        /** "1 小时 39 分 · 接上时已有 194 条消息 · 2460 帧"。 */
        public String detail() {
            long min = durationMs / 60000L;
            return (min >= 60 ? (min / 60) + " 小时 " + (min % 60) + " 分"
                    : min + " 分钟")
                    + " · 接上时已有 " + snapshotMsgs + " 条消息"
                    + " · " + frames + " 帧";
        }
    }

    /** {@link #open} 的返回：元信息 + 已经**定位到第一帧**的流。 */
    public static final class Opened {
        public final Meta meta;
        public final InputStream stream;

        Opened(Meta meta, InputStream stream) {
            this.meta = meta;
            this.stream = stream;
        }
    }

    /**
     * 解析第一行元信息。不是 {@code RCLOG1} 开头就返回 null。
     */
    public static Meta parseMeta(String line) {
        if (line == null) {
            return null;
        }
        String s = line.trim();
        if (!s.startsWith(MAGIC)) {
            return null;
        }
        String js = s.substring(MAGIC.length()).trim();
        if (js.length() == 0) {
            return null;
        }
        try {
            JSONObject o = new JSONObject(js);
            return new Meta(o.optString("name", ""), o.optString("date", ""),
                    o.optString("session", ""), o.optLong("snapshotMs", 0L),
                    o.optLong("durationMs", 0L), o.optInt("snapshotMsgs", 0),
                    o.optInt("frames", 0), o.optString("note", ""));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 认得出 gzip 就解开，认不出就当纯文本。
     *
     * {@code .rclog} 平时是 gzip 的（4 MB 压到 400 KB），但不压缩的也允许 ——
     * 用户手搓一个、或者以后换压缩方式，都不该让 App 罢工。
     */
    public static InputStream maybeGunzip(InputStream in) throws IOException {
        if (in == null) {
            return null;
        }
        java.io.PushbackInputStream p = new java.io.PushbackInputStream(in, 2);
        byte[] head = new byte[2];
        int n = 0;
        while (n < 2) {
            int got = p.read(head, n, 2 - n);
            if (got < 0) {
                break;
            }
            n += got;
        }
        if (n > 0) {
            p.unread(head, 0, n);
        }
        if (n == 2 && (head[0] & 0xff) == 0x1f && (head[1] & 0xff) == 0x8b) {
            return new java.util.zip.GZIPInputStream(p);
        }
        return p;
    }

    /**
     * 打开一个 {@code .rclog}：解压（如果需要）、取元信息，
     * 返回一个**正好停在第一帧开头**的流。
     *
     * ★ 只窥探开头 6 个字节来判断"有没有元信息头"。
     *   不能"先读一行再看是不是头"：没有头的时候，第一行是一帧完整快照，
     *   巴林那场的快照帧有 80 KB，读进来再想塞回去就没法塞了。
     */
    public static Opened open(InputStream raw) throws IOException {
        InputStream s = maybeGunzip(raw);
        java.io.PushbackInputStream p =
                new java.io.PushbackInputStream(s, MAGIC.length() + 2);
        byte[] magic = MAGIC.getBytes("UTF-8");
        byte[] head = new byte[magic.length];
        int n = 0;
        while (n < head.length) {
            int got = p.read(head, n, head.length - n);
            if (got < 0) {
                break;
            }
            n += got;
        }
        if (n == head.length && java.util.Arrays.equals(head, magic)) {
            // 是元信息头：把这一行剩下的读完
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            int b;
            while ((b = p.read()) >= 0 && b != '\n') {
                bo.write(b);
                if (bo.size() > 16000) {
                    break;              // 第一行不该这么长
                }
            }
            Meta m = parseMeta(MAGIC + new String(bo.toByteArray(), "UTF-8"));
            return new Opened(m, p);
        }
        // 不是头：把这几个字节原样塞回去，流仍从第一帧开始
        if (n > 0) {
            p.unread(head, 0, n);
        }
        return new Opened(null, p);
    }

    /** 只要元信息（设置页选完文件后显示用）。读不出来返回 null。 */
    public static Meta readMeta(InputStream raw) {
        InputStream in = raw;
        try {
            Opened o = open(in);
            return o.meta;
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // 关不掉不值得报
                }
            }
        }
    }

    /** SignalR 的记录分隔符。 */
    public static final char RS = 0x1e;

    /**
     * 两次刷新之间至少隔这么久。高倍速下一秒钟能过几百帧，
     * 每帧都去 post 一次界面太浪费 —— 攒起来一次喂。
     * 测试里传 0 就是一帧一喂（这样才能逐帧观察到中间状态）。
     */
    private static final long COALESCE_MS = 15L;

    private final Opener opener;
    private final F1Client.Listener listener;
    private final int speed;
    /**
     * 回放总时长，只用来算进度百分比。
     *
     * ★ 不是 final：调用方多半**不知道**时长（文件是用户临时挑的，
     *   读它得开流、可能还得读网盘，不能在主线程干）。所以传 0 进来，
     *   由 {@link #runForever} 读到文件自带的元信息后自己补上。
     */
    private volatile long spanMs;
    private final long coalesceMs;
    private final DateFormat utcFmt;
    private final F1Feed feed = new F1Feed();

    private volatile boolean closed;
    private volatile int percent;
    private volatile long positionMs;
    private volatile Meta meta;
    private String lastError = "";
    /** 消息时间戳的单调计数器，见 {@link #nextStamp}。 */
    private long stampSeq;

    /**
     * @param opener  怎么拿包
     * @param listener 和 {@link F1Client} 用同一个回调接口
     * @param speed   倍速，&lt;=0 当 1
     * @param spanMs  包的时长（用于进度显示），0 = 不知道
     */
    public ReplayClient(Opener opener, F1Client.Listener listener, int speed,
                        long spanMs) {
        this(opener, listener, speed, spanMs, COALESCE_MS);
    }

    /**
     * @param coalesceMs 两次 {@code onFeed} 之间最少隔几毫秒；0 = 每帧都回调。
     *                   回放调试/单元测试用 0，真机上看画面用默认值。
     */
    public ReplayClient(Opener opener, F1Client.Listener listener, int speed,
                        long spanMs, long coalesceMs) {
        this.opener = opener;
        this.listener = listener;
        this.speed = speed <= 0 ? 1 : speed;
        this.spanMs = spanMs;
        this.coalesceMs = coalesceMs;
        // ★ 必须带毫秒。原因见 nextStamp —— 秒级精度装不下同一批里的多条消息，
        //   会撞成一模一样的 Utc，然后被 MessageStore 当重复消息丢掉。
        SimpleDateFormat f = new SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        this.utcFmt = f;
    }

    /** 只用一秒钟放完（测试用）。 */
    public ReplayClient(Opener opener, F1Client.Listener listener) {
        this(opener, listener, 1000000, 0);
    }

    public F1Feed feed() {
        return feed;
    }

    public String lastError() {
        return lastError;
    }

    /**
     * 文件自带的元信息。文件里没有 {@code RCLOG1} 头就是 null，
     * 主界面这时候只能显示文件名。
     */
    public Meta meta() {
        return meta;
    }

    public int speed() {
        return speed;
    }

    /** 0–100。 */
    public int percent() {
        return percent;
    }

    public long positionMs() {
        return positionMs;
    }

    public void stop() {
        closed = true;
    }

    /** 放完一遍就结束，不会循环，也不会重连。 */
    public void runForever() {
        BufferedReader r = null;
        List<String> batch = new ArrayList<String>();
        try {
            // ★ 文件里第一行可能是元信息头（RCLOG1 {...}），open() 会认出它、
            //   顺手摘掉，交给我们的流正好停在第一帧开头。
            Opened op = open(opener.open());
            this.meta = op.meta;
            // 调用方不知道时长（也没法在主线程去读），用文件自带的
            if (spanMs <= 0 && op.meta != null && op.meta.durationMs > 0) {
                spanMs = op.meta.durationMs;
            }
            r = new BufferedReader(new InputStreamReader(op.stream, "UTF-8"),
                    1 << 16);
            long baseWall = 0;
            long startMs = -1;
            long flushAt = 0;
            String line;
            while (!closed && (line = r.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0) {
                    continue;
                }
                int brace = line.indexOf('{');
                if (brace <= 0) {
                    continue;
                }
                long t = parseOffset(line.substring(0, brace));
                if (startMs < 0) {
                    startMs = t;
                    baseWall = System.currentTimeMillis();
                    if (listener != null) {
                        listener.onOpen();
                    }
                }

                // 这一帧"该在什么时候出现"。speed 就是这么快。
                long due = baseWall + (t - startMs) / speed;
                long wait = due - System.currentTimeMillis();
                while (wait > 0 && !closed) {
                    Thread.sleep(wait > 100 ? 100 : wait);
                    wait = due - System.currentTimeMillis();
                }
                if (closed) {
                    break;
                }

                batch.add(line.substring(brace));
                long now = System.currentTimeMillis();
                if (coalesceMs <= 0 || now >= flushAt) {
                    flush(batch, now);
                    flushAt = now + coalesceMs;
                }

                positionMs = t - startMs;
                if (spanMs > 0) {
                    long pc = positionMs * 100L / spanMs;
                    percent = (int) (pc > 100 ? 100 : pc);
                }
            }
            if (!closed) {
                flush(batch, System.currentTimeMillis());
                percent = 100;
                if (listener != null) {
                    listener.onFeed(feed);
                    listener.onClose();
                }
            }
        } catch (Throwable t) {
            lastError = describe(t);
            if (listener != null) {
                listener.onError(lastError);
                listener.onClose();
            }
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (IOException ignored) {
                    // 关不掉也不值得报
                }
            }
        }
    }

    /** 把攒下的一批帧喂进去。 */
    private void flush(List<String> batch, long now) {
        boolean changed = false;
        for (int i = 0; i < batch.size(); i++) {
            String payload = batch.get(i);
            int from = 0;
            while (from < payload.length()) {
                int rs = payload.indexOf(RS, from);
                String rec;
                if (rs < 0) {
                    rec = payload.substring(from);
                    from = payload.length();
                } else {
                    rec = payload.substring(from, rs);
                    from = rs + 1;
                }
                rec = rec.trim();
                if (rec.length() == 0) {
                    continue;
                }
                JSONObject o;
                try {
                    o = new JSONObject(rec);
                } catch (Exception e) {
                    continue;               // 不是 JSON 就跳过，别断流
                }
                int type = o.optInt("type", -1);
                if (type == 6) {
                    continue;               // 心跳。回不回都行，回放里直接忽略
                }
                if (type == 7) {
                    closed = true;          // 服务端要求关闭
                    break;
                }
                if (type == 1) {
                    rewriteUtc(o);
                }
                changed |= feed.onRecord(o);
            }
        }
        batch.clear();
        if (changed && listener != null) {
            listener.onFeed(feed);
        }
    }

    /**
     * 把这条增量里的赛事控制消息时间改成"现在"。
     *
     * <p>快照（type 3）不走这里 —— 见类注释。
     *
     * <p>★ 时间戳必须**逐条唯一**。第一版图省事，给一整批用同一个
     * {@code stamp}，结果当场翻车：F1 的旗语消息大量重复
     * （"CLEAR IN TRACK SECTOR 5" 一场里出现好几次），而
     * {@link MessageStore} 的去重键是 {@code Utc|Flag|Sector|Text} ——
     * 时间一撞，这些消息就被当重复的丢了，327 条只剩 286 条。
     * 高倍速下真机上也会这样，而且丢的偏偏是"重复出现的旗语"，
     * 正好是最该看到的东西。
     */
    private void rewriteUtc(JSONObject rec) {
        // ★ 流名/载荷的取法不能自己写死 —— 官方现在把增量发成
        //   target="feed" + arguments[流名, 载荷, 时间戳]，老形状才是
        //   target=流名 + arguments[0]=载荷。这段以前只认老形状，于是**新形状
        //   录成的回放包**不会重写时间戳（消息按原时间落进"历史"里，
        //   告警不响、列表排序也怪）。交给 F1Feed 的公共取法，两处共用一份判断。
        if (!"RaceControlMessages".equals(F1Feed.streamName(rec))) {
            return;
        }
        JSONObject payload = F1Feed.payloadOf(rec);
        if (payload == null) {
            return;
        }
        // 实测两种形状都有：数组，以及以行号为键的对象
        JSONArray arr = payload.optJSONArray("Messages");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                putUtc(arr.optJSONObject(i), nextStamp());
            }
            return;
        }
        JSONObject map = payload.optJSONObject("Messages");
        if (map != null) {
            Iterator<String> it = map.keys();
            while (it.hasNext()) {
                putUtc(map.optJSONObject(it.next()), nextStamp());
            }
        }
    }

    /**
     * 下一个"现在"：严格单调递增的 UTC 时间戳（毫秒精度）。
     *
     * 起点是当前墙上时间，之后每条消息 +1 ms。这样既全在"最近"里
     * （告警会响），又互不相同（不会被去重误杀）。整场回放累计只漂移
     * 几百毫秒，肉眼无感。
     */
    private String nextStamp() {
        long now = System.currentTimeMillis();
        stampSeq = stampSeq < now ? now : stampSeq + 1;
        return utcFmt.format(new Date(stampSeq));
    }

    private static void putUtc(JSONObject m, String stamp) {
        if (m == null) {
            return;
        }
        try {
            m.put("Utc", stamp);
        } catch (Exception ignored) {
            // put 不该失败；真失败就保留原时间，也不值当断流
        }
    }

    /** 解析 `HH:MM:SS.mmm`。 */
    static long parseOffset(String s) {
        if (s == null || s.length() < 12) {
            return 0L;
        }
        try {
            int h = Integer.parseInt(s.substring(0, 2));
            int mi = Integer.parseInt(s.substring(3, 5));
            int sec = Integer.parseInt(s.substring(6, 8));
            int ms = Integer.parseInt(s.substring(9, 12));
            return ((h * 60L + mi) * 60L + sec) * 1000L + ms;
        } catch (Exception e) {
            return 0L;
        }
    }

    private static String describe(Throwable t) {
        String m = t.getMessage();
        return m == null || m.length() == 0 ? t.toString() : m;
    }
}