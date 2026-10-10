package com.haf1.racecontrol.desktop;

import java.util.List;

import com.haf1.racecontrol.F1Client;
import com.haf1.racecontrol.F1Feed;
import com.haf1.racecontrol.F1Layout;
import com.haf1.racecontrol.FeedSource;
import com.haf1.racecontrol.RaceMessage;
import com.haf1.racecontrol.TrackState;
import com.haf1.racecontrol.Translator;

/**
 * 把 {@link F1Feed} 的累积状态编成前端要的 JSON。
 *
 * ## 线程安全
 * 数据线程在写、HTTP 线程在读。这里**不加自己的锁**，因为
 * {@code F1Feed}/{@code TrackState}/{@code MessageStore} 的公开方法本来就都是
 * {@code synchronized} 的 —— 逐个取值时各自原子。可能读到"半新半旧"的一组值
 * （车手列表是这一帧的、消息表还是上一帧的），但每个值本身都自洽，
 * 界面上下一次推送就追平了。为这个加一把大锁反而会拖住数据线程。
 *
 * ## 为什么不下发原始 stream
 * {@code F1Feed.stream(name)} 能拿到合并后的原始 JSON，但 TimingData 一份就
 * 有 22 辆车全部字段，10 Hz 推给浏览器是浪费。这里只下发**面板实际要用的**
 * 派生字段（约 10~20 KB）；真要原始流有 {@link #fullJson()} 单独取。
 */
public final class AppCore implements F1Client.Listener {

    /**
     * 下发给界面的消息条数上限（最新在前）。
     * 60 是权衡：每条约 470 字节，200 条会让每次推送 ~95 KB、10 Hz 下就是
     * 1 MB/s 的局域网流量；界面只需要能显示"最近发生了什么"和最新一条横幅。
     */
    private static final int MESSAGE_LIMIT = 60;

    private FeedSource source;
    private final F1Feed emptyFeed = new F1Feed();
    private final boolean replay;
    private final String replayName;
    private final String appVersion;

    private volatile boolean open;
    private volatile String error = "";
    private volatile long updates;
    private volatile long lastUpdateAt;

    public AppCore(boolean replay, String replayName, String appVersion) {
        this.replay = replay;
        this.replayName = replayName == null ? "" : replayName;
        this.appVersion = appVersion;
    }

    /**
     * 接上数据源。
     *
     * 为什么要分两步：{@code new F1Client(listener)} 需要 listener，而 listener
     * 就是本类；本类又要拿 {@code source.feed()}。构造顺序天生是个环，
     * 与其搞一个"转发 listener + 数组占位"的绕法，不如明说分两步。
     */
    public void attach(FeedSource s) {
        this.source = s;
    }

    // ------------------------------------------------------------------
    // F1Client.Listener
    // ------------------------------------------------------------------

    @Override
    public void onOpen() {
        open = true;
        error = "";
        lastUpdateAt = System.currentTimeMillis();
    }

    @Override
    public void onFeed(F1Feed feed) {
        updates++;
        lastUpdateAt = System.currentTimeMillis();
    }

    @Override
    public void onError(String message) {
        error = message == null ? "" : message;
    }

    @Override
    public void onClose() {
        open = false;
    }

    // ------------------------------------------------------------------
    // 对外
    // ------------------------------------------------------------------

    public FeedSource source() {
        return source;
    }

    public F1Feed feed() {
        FeedSource s = source;
        return s == null ? emptyFeed : s.feed();
    }

    /**
     * 显示延时（秒）。回放模式下没有延时（回放本身就是时间轴）。
     *
     * @see com.haf1.racecontrol.DelayGate
     */
    public int delaySeconds() {
        FeedSource s = source;
        return s instanceof F1Client ? ((F1Client) s).delaySeconds() : 0;
    }

    /** 还在队列里压着多少条（界面显示"正在滞后"用）。 */
    public int delayQueued() {
        FeedSource s = source;
        return s instanceof F1Client ? ((F1Client) s).queuedRecords() : 0;
    }

    /**
     * 改显示延时。**立即生效，不用重连** —— 用户可以边看边调。
     *
     * @return 是否真的应用了。回放模式下返回 false（回放本身就是时间轴，
     *         延时没有意义）—— 调用方据此**不要**去落盘，否则会把直播模式
     *         存下来的值抹成 0（这是验证者读代码时抓到的）。
     */
    public boolean setDelaySeconds(int sec) {
        FeedSource s = source;
        if (!(s instanceof F1Client)) {
            return false;
        }
        ((F1Client) s).setDelaySeconds(sec);
        return true;
    }

    public boolean isOpen() {
        return open;
    }

    /**
     * 是否"有数据可用"。回放放完了也算。
     *
     * 不能用 {@link #isOpen()} 单独判断：数据源结束时（回放放完、
     * 或实时连接断开进入重连）会回调 {@code onClose()} 把 open 置回 false，
     * 但那一刻 **F1Feed 里仍然是一份完整可用的状态** ——
     * 只看 open 会让 /api/health 在一个已经跑完的回放上谎报"没开"。
     */
    public boolean isLive() {
        return open || updates > 0;
    }

    /** 每次 onFeed 自增。界面拿它判断"有没有新东西"，避免无谓重绘。 */
    public long updates() {
        return updates;
    }

    public long lastUpdateAt() {
        return lastUpdateAt;
    }

    public String error() {
        FeedSource s = source;
        String e = s == null ? null : s.lastError();
        if (e == null || e.length() == 0) {
            e = error;
        }
        return e == null ? "" : e;
    }

    /** 界面要的那一份状态。 */
    public String stateJson() {
        F1Feed f = feed();
        TrackState t = f.track;

        Json.Obj o = new Json.Obj();
        o.put("app", "F1-RaceControl-Desktop");
        o.put("version", appVersion);
        o.put("replay", replay);
        o.put("replayName", replayName);
        // ★ "open" 用 isLive() 而不是裸的 open：
        //   数据源结束时 onClose() 会把 open 置回 false，但 F1Feed 里
        //   仍然是一份完整状态，界面照常能显示。裸的 open 会在
        //   "回放放完了"和"实时正在重连"这两种情况下谎报没数据。
        o.put("open", isLive());
        o.put("listenerOpen", open);
        o.put("error", error());
        o.put("updates", updates);
        o.put("lastUpdateAt", lastUpdateAt);
        o.put("serverTime", System.currentTimeMillis());
        // 显示延时（秒）—— 网页上那对 −/＋ 按钮和"正在滞后"的提示都读它
        o.put("delaySec", delaySeconds());
        o.put("delayQueued", delayQueued());

        Json.Obj race = new Json.Obj();
        race.put("meeting", f.meetingName());
        race.put("circuit", f.circuitName());
        race.put("session", f.sessionName());
        race.put("status", f.sessionStatus());
        race.put("remaining", f.remaining());
        race.put("lap", f.currentLap());
        race.put("totalLaps", f.totalLaps());
        race.put("trackStatusCode", f.trackStatusCode());
        race.put("sectorCount", f.sectorCount());
        // ★ 拼好的 JSON 一律走 raw()。用 put(k, String) 会被当成普通字符串
        //   加引号，前端拿到的是字符串 '[]' 而不是数组 —— 这个坑真踩过，
        //   症状是"接口全 200、页面一片空白"。
        race.raw("topThree", new Json.Arr().addStrings(f.topThree()).done());
        o.raw("race", race.done());

        Json.Obj track = new Json.Obj();
        track.put("level", t.level());
        track.put("globalLevel", t.globalLevel());
        track.put("label", t.label());
        track.put("detail", t.detail());
        track.raw("kinds", new Json.Arr().addStrings(t.presentKinds()).done());
        track.raw("yellowSectors",
                new Json.Arr().addInts(t.yellowSectors()).done());
        track.raw("doubleYellowSectors",
                new Json.Arr().addInts(t.doubleYellowSectors()).done());
        track.put("updatedAt", t.updatedAt());
        // ★ 颜色直接下发安卓算好的 ARGB 值 —— 网页不另配一套色板，
        //   否则"同款 UI"从颜色这一层就开始漂了。
        track.put("color", t.color());
        track.put("textColor", t.textColor());
        o.raw("track", track.done());

        // 旗语栏的分段几何用 F1Layout 算（和安卓同一份算术），
        // 宽度用千分比，前端按容器宽度缩放。
        o.raw("bar", barJson(t));

        // 圆环（赛道图降级方案）的角度同样来自 F1Layout。
        // ★ 约定：0 度 = 12 点方向、顺时针 —— 和 Canvas 的 0 度 = 3 点不同，
        //   前端画的时候要减 90。F1Layout 的注释专门警告过这个 off-by-90。
        int secs = f.sectorCount();
        Json.Obj ring = new Json.Obj();
        ring.put("count", secs);
        ring.put("sweep", F1Layout.arcSweep(secs));
        ring.raw("starts", new Json.Arr().addFloats(F1Layout.ringStarts(secs)).done());
        o.raw("ring", ring.done());

        Json.Obj weather = new Json.Obj();
        weather.put("air", f.airTemp());
        weather.put("track", f.trackTemp());
        weather.put("text", f.weatherText());
        o.raw("weather", weather.done());

        o.raw("cars", carsJson(f.cars()));
        o.raw("messages", messagesJson(f.messages.sortedDesc()));
        return o.done();
    }

    /** 原始合并流（大，按需取；给以后要加面板用）。 */
    public String fullJson() {
        F1Feed f = feed();
        Json.Obj o = new Json.Obj();
        o.put("app", "F1-RaceControl-Desktop");
        o.put("version", appVersion);
        for (int i = 0; i < F1Feed.STREAMS.length; i++) {
            String name = F1Feed.STREAMS[i];
            o.raw(name, Json.of(f.stream(name)));
        }
        return o.done();
    }

    // ------------------------------------------------------------------

    /**
     * 顶部旗语栏的分段。几何交给 {@code F1Layout}（安卓同一份），
     * 所以网页上的分隔线位置和安卓**算出来就是同一个数**。
     */
    private static String barJson(TrackState t) {
        java.util.List<String> kinds = t.presentKinds();
        int n = kinds.size();
        Json.Arr a = new Json.Arr();
        for (int i = 0; i < n; i++) {
            String kind = kinds.get(i);
            java.util.List<Integer> sectors = t.sectorsOf(kind);
            Json.Obj o = new Json.Obj();
            o.put("kind", kind);
            o.put("left", F1Layout.barSegmentLeft(i, n, 1000f));
            o.put("width", F1Layout.barSegmentWidth(n, 1000f));
            o.put("sectors", F1Layout.sectorList(sectors, 12));
            o.raw("sectorNos", new Json.Arr().addInts(sectors).done());
            o.put("count", sectors.size());
            a.raw(o.done());
        }
        return a.done();
    }

    private static String carsJson(List<F1Feed.Car> cars) {
        Json.Arr a = new Json.Arr();
        for (int i = 0; i < cars.size(); i++) {
            F1Feed.Car c = cars.get(i);
            Json.Obj o = new Json.Obj();
            o.put("number", c.number);
            o.put("tla", c.tla);
            o.put("team", c.team);
            o.put("teamColour", c.teamColour);
            o.put("fullName", c.fullName);
            o.put("position", c.position);
            o.put("inPit", c.inPit);
            o.put("retired", c.retired);
            o.put("compound", c.compound);
            o.put("compoundCn", F1Feed.compoundCn(c.compound));
            o.put("tyreLaps", c.tyreLaps);
            o.put("stint", c.stint);
            o.put("pitStops", c.pitStops);
            o.put("gap", c.gap);
            o.put("interval", c.interval);
            o.put("bestLap", c.bestLap);
            o.put("label", c.label());
            a.raw(o.done());
        }
        return a.done();
    }

    private static String messagesJson(List<RaceMessage> msgs) {
        Json.Arr a = new Json.Arr();
        int n = Math.min(msgs.size(), MESSAGE_LIMIT);
        for (int i = 0; i < n; i++) {
            RaceMessage m = msgs.get(i);
            Json.Obj o = new Json.Obj();
            o.put("time", m.time);
            o.put("utc", m.utc);
            o.put("state", m.state);
            o.put("message", m.message);
            o.put("text", m.text());
            // ★ 中文简述必须走 Translator —— 安卓那边也是这么用的
            //   （F1MainActivity.java:769 `Translator.gloss(m.text())`）。
            //   m.text() 本身只是英文原文，别把它当中文。
            o.put("gloss", Translator.gloss(m.text()));
            o.put("category", m.category);
            o.put("flag", m.flag);
            o.put("scope", m.scope);
            o.put("sector", m.sector);
            o.put("sectorNo", m.sectorNo());
            o.put("carNumber", m.carNumber);
            o.put("eventId", m.eventId);
            o.put("sequence", m.sequence);
            o.put("key", m.key());
            a.raw(o.done());
        }
        return a.done();
    }
}