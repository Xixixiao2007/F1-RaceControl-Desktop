package com.haf1.racecontrol;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.text.SimpleDateFormat;
import java.util.TimeZone;

/**
 * F1 官方实时流的**状态合并器**。
 *
 * ## 为什么要单独一个类
 * 网络那一半（{@link F1Client}）碰 socket、碰 Android，没法离线测；
 * 而"把流里的增量合并成状态"这一半是**纯逻辑**，用 org.json 就够 ——
 * 所以拆出来，让它被单测覆盖。踩过的坑（增量不是全量）就靠这一半兜住。
 *
 * ## 协议（实测）
 * SignalR Core 的记录用 0x1e 分隔，三种：
 *   type 3  快照   {"type":3,"invocationId":"0","result":{"<流名>": <整份数据>, ...}}
 *   type 1  增量   {"type":1,"target":"<流名>","arguments":[<该流的增量>]}
 *   type 6  心跳   {"type":6}   ← **必须回一条**，否则会被服务端断开
 *
 * ## ★ 核心陷阱：这些流全是**增量**的
 * 订阅时给一份完整快照，之后每帧**只带变化的字段**。举两个真实形状：
 *
 *     DriverList 增量：{"16":{"Tla":"LEC"}}          ← 只有变了的字段
 *     TimingAppData ：{"Lines":{"1":{"Stints":{"3":{"TotalLaps":13}}}}}
 *
 * 所以必须**深合并**（逐层按 key 合并），直接替换会把整份状态清掉 ——
 * 表现是"开着开着车手全没了、轮胎全空了"。
 *
 * 唯一的例外是 `RaceControlMessages`：它的增量是**新消息列表**，不是状态，
 * 所以要往 {@link MessageStore} 里**追加**（靠 eventId 去重），不能合并。
 */
public class F1Feed {

    /** 订阅哪些流。全在官方公开组里，不需要 F1TV。 */
    public static final String[] STREAMS = {
            "RaceControlMessages", "TrackStatus", "SessionStatus", "SessionInfo",
            "LapCount", "DriverList", "TimingData", "TimingAppData",
            "ExtrapolatedClock", "WeatherData",
            // ★ 这两条是补上的，以前漏了 —— 后果是"最快圈面板"和"前三名"永远是空的：
            //   cars() 的 bestLap 只从 TimingStats 取（见下面 bestLap 那段），
            //   topThree() 只从 TopThree 取。没订这两个流，面板就一直空着，
            //   而实况下"面板是空的"和"这节比赛还没跑出成绩"长得一模一样，
            //   所以肉眼根本发现不了。是拿归档流名跟订阅列表逐条对照才揪出来的。
            "TimingStats", "TopThree",
    };

    /** 区段旗语状态机（用户要求：双黄收到普通黄旗 = 降级为单黄）。 */
    public final TrackState track = new TrackState();
    /** 赛事控制消息列表（按 eventId 去重）。 */
    public final MessageStore messages = new MessageStore();

    /** 各流的累积状态。深合并写进这里。 */
    private final JSONObject state = new JSONObject();
    /** 本场出现过的最大区段号 —— 圆环按它分配段数（无需任何外部表）。 */
    private int maxSector = 0;
    /** 监听器：有新消息时通知界面。 */
    private Listener listener;
    private String lastError = "";

    public interface Listener {
        void onRaceMessage(RaceMessage m);
        void onStatusChanged();
    }

    public synchronized void setListener(Listener l) {
        this.listener = l;
    }

    public synchronized String lastError() {
        return lastError;
    }

    // ------------------------------------------------------------------
    // 喂数据
    // ------------------------------------------------------------------

    /** 处理一条已经解析好的 SignalR 记录；返回 true 表示状态有变化。 */
    public synchronized boolean onRecord(JSONObject rec) {
        if (rec == null) {
            return false;
        }
        int type = rec.optInt("type", -1);
        if (type == 3) {
            JSONObject result = rec.optJSONObject("result");
            return onSnapshot(result);
        }
        if (type == 1) {
            return onDelta(streamName(rec), payloadOf(rec));
        }
        return false;
    }

    /**
     * type=1 记录里的**流名**。官方有两种形状，都得认：
     *
     * <pre>
     * 老：{"type":1,"target":"RaceControlMessages","arguments":[{"Messages":[…]}]}
     * 新：{"type":1,"target":"feed","arguments":["RaceControlMessages",{…},"&lt;utc&gt;"]}
     * </pre>
     *
     * ★ 新形状是 2026-10 实测出来的（原始抓包见 dsh\tools\probe_live_raw.py）：
     * target 固定是 "feed"，**流名挪到了 arguments[0]**，arguments[1] 才是载荷，
     * arguments[2] 是服务端时间戳。
     *
     * 这个变化当年把实时数据整个打断了，而且**报不出任何错**：老代码写的是
     * "target 就是流名、载荷是 arguments[0]"，遇到新形状就成了
     * {@code onDelta("feed", null)} —— 每次都"没有变化"，于是快照之后再也不刷新，
     * 服务器日志干干净净。用户的原话是"接不到实时数据只有历史数据"。
     *
     * 老形状必须继续认：早期录制的回放包（.rclog）里就是老形状。
     */
    public static String streamName(JSONObject rec) {
        if (rec == null) {
            return "";
        }
        String target = rec.optString("target", "");
        if (!"feed".equals(target)) {
            return target;                     // 老形状：target 本身就是流名
        }
        JSONArray args = rec.optJSONArray("arguments");
        return (args == null || args.length() == 0) ? "" : args.optString(0, "");
    }

    /** type=1 记录里的**载荷**（和 {@link #streamName} 配套，两种形状都认）。 */
    public static JSONObject payloadOf(JSONObject rec) {
        if (rec == null) {
            return null;
        }
        JSONArray args = rec.optJSONArray("arguments");
        if (args == null || args.length() == 0) {
            return null;
        }
        if (!"feed".equals(rec.optString("target", ""))) {
            return args.optJSONObject(0);      // 老形状
        }
        return args.length() >= 2 ? args.optJSONObject(1) : null;   // 新形状
    }

    /** type 3：整份快照，把每个流都过一遍。 */
    public synchronized boolean onSnapshot(JSONObject result) {
        if (result == null) {
            return false;
        }
        boolean changed = false;
        Iterator<String> it = result.keys();
        while (it.hasNext()) {
            String stream = it.next();
            changed |= apply(stream, result.optJSONObject(stream));
        }
        return changed;
    }

    /** type 1：单个流的增量。 */
    public synchronized boolean onDelta(String stream, JSONObject payload) {
        return apply(stream, payload);
    }

    private boolean apply(String stream, JSONObject payload) {
        if (stream == null || stream.length() == 0 || payload == null) {
            return false;
        }
        if ("RaceControlMessages".equals(stream)) {
            return addMessages(payload);
        }
        JSONObject cur = state.optJSONObject(stream);
        if (cur == null) {
            cur = new JSONObject();
            try {
                state.put(stream, cur);
            } catch (JSONException e) {
                // 放进去的是 JSONObject，理论上不可能失败；但 put 是受检异常，
                // 不处理就编译不过（Android 的真实签名就是这样）。
                lastError = "状态写入失败：" + e.getMessage();
                return false;
            }
        }
        deepMerge(cur, payload);
        if ("TrackStatus".equals(stream)) {
            // ★ 全赛道黄旗 / 红旗 / 安全车 / VSC 只在这里出现
            //   （RaceControlMessages 里没有 Scope=Track 的黄旗）。
            track.onTrackStatus(cur.optString("Status", ""));
        }
        return true;
    }

    /**
     * 深合并：逐层按 key 合并，只有叶子才覆盖。
     *
     * 这是"增量流"能对的核心。数组按整体覆盖（这些流里没有需要逐项合并的数组）。
     */
    static void deepMerge(JSONObject dst, JSONObject src) {
        Iterator<String> it = src.keys();
        while (it.hasNext()) {
            String k = it.next();
            JSONObject sv = src.optJSONObject(k);
            if (sv != null) {
                JSONObject dv = dst.optJSONObject(k);
                if (dv == null) {
                    dv = new JSONObject();
                    try {
                        dst.put(k, dv);
                    } catch (JSONException ignored) {
                        continue;
                    }
                }
                deepMerge(dv, sv);
            } else {
                try {
                    dst.put(k, src.get(k));
                } catch (Exception ignored) {
                    // 不该发生；真发生了也不值得让整条流断掉
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 赛事控制消息
    // ------------------------------------------------------------------

    private boolean addMessages(JSONObject payload) {
        // ★ 用 optJSONArray / optJSONObject，**不要**用 opt() + instanceof：
        //   真实 org.json 的 opt() 对数组返回 JSONArray，但测试桩（以及别的实现）
        //   可能返回原生 List/Map —— 那样 instanceof JSONArray 就是 false，
        //   整份消息会被静默丢掉（一开始就是这么错的）。
        List<JSONObject> list = new ArrayList<JSONObject>();
        JSONArray a = payload.optJSONArray("Messages");
        if (a != null) {
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null) {
                    list.add(o);
                }
            }
        } else {
            JSONObject m = payload.optJSONObject("Messages");
            if (m != null) {
                Iterator<String> it = m.keys();
                while (it.hasNext()) {
                    JSONObject o = m.optJSONObject(it.next());
                    if (o != null) {
                        list.add(o);
                    }
                }
            }
        }
        // 按 Utc 排序：快照里是乱序的 map，必须按时间喂状态机，
        // 否则"先 CLEAR 后 YELLOW"会被读成相反的次序。
        Collections.sort(list, new Comparator<JSONObject>() {
            public int compare(JSONObject a, JSONObject b) {
                return a.optString("Utc", "").compareTo(b.optString("Utc", ""));
            }
        });

        boolean changed = false;
        for (int i = 0; i < list.size(); i++) {
            RaceMessage m = toMessage(list.get(i));
            if (m == null) {
                continue;
            }
            int sec = m.sectorNo();
            if (sec > maxSector) {
                maxSector = sec;
            }
            if (messages.add(m)) {
                track.onMessage(m);
                changed = true;
                if (listener != null) {
                    listener.onRaceMessage(m);
                }
            }
        }
        if (changed && listener != null) {
            listener.onStatusChanged();
        }
        return changed;
    }

    /** 把 F1 的一条消息转成 App 内部的 {@link RaceMessage}。 */
    static RaceMessage toMessage(JSONObject o) {
        if (o == null) {
            return null;
        }
        String utc = o.optString("Utc", "");
        String text = o.optString("Message", "");
        if (utc.length() == 0 || text.length() == 0) {
            return null;
        }
        long t = parseUtc(utc);
        String flag = o.optString("Flag", "");
        String sector = "";
        if (o.has("Sector") && !o.isNull("Sector")) {
            int s = o.optInt("Sector", 0);
            if (s > 0) {
                sector = String.valueOf(s);
            }
        }
        String car = o.optString("RacingNumber", "");
        // F1 不给 event_id，自己拼一个稳定的：同一条消息重复推过来要能去重
        String eventId = utc + "|" + flag + "|" + sector + "|" + text;
        return new RaceMessage(t, text, utc, text,
                o.optString("Category", ""), flag, o.optString("Scope", ""),
                sector, car, utc, eventId, 0);
    }

    /** F1 的 `Utc` 有的带 Z、有的不带 —— 不带的一律按 UTC 理解（实测两者都有）。 */
    static long parseUtc(String utc) {
        if (utc == null) {
            return 0L;
        }
        String s = utc.trim();
        if (s.length() == 0) {
            return 0L;
        }
        if (s.endsWith("Z") || s.endsWith("z")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.indexOf('+') > 0) {
            s = s.substring(0, s.indexOf('+'));
        }
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(s).getTime();
        } catch (Exception e) {
            return 0L;
        }
    }

    // ------------------------------------------------------------------
    // 给界面用
    // ------------------------------------------------------------------

    public synchronized JSONObject stream(String name) {
        return state.optJSONObject(name);
    }

    /** 官方轨道级状态码：1/8 全清 2 黄 4 安全车 5 红旗 6 VSC 7 VSC 结束。 */
    public synchronized String trackStatusCode() {
        JSONObject ts = state.optJSONObject("TrackStatus");
        return ts == null ? "" : ts.optString("Status", "");
    }

    public synchronized int currentLap() {
        JSONObject lc = state.optJSONObject("LapCount");
        return lc == null ? 0 : lc.optInt("CurrentLap", 0);
    }

    public synchronized int totalLaps() {
        JSONObject lc = state.optJSONObject("LapCount");
        return lc == null ? 0 : lc.optInt("TotalLaps", 0);
    }

    /** 例如 "Bahrain Grand Prix"。 */
    public synchronized String meetingName() {
        JSONObject si = state.optJSONObject("SessionInfo");
        JSONObject m = si == null ? null : si.optJSONObject("Meeting");
        return m == null ? "" : m.optString("Name", "");
    }

    public synchronized String circuitName() {
        JSONObject si = state.optJSONObject("SessionInfo");
        JSONObject m = si == null ? null : si.optJSONObject("Meeting");
        JSONObject c = m == null ? null : m.optJSONObject("Circuit");
        return c == null ? "" : c.optString("ShortName", "");
    }

    /** 环节名，例如 "Race" / "Qualifying" / "Practice 1"。 */
    public synchronized String sessionName() {
        JSONObject si = state.optJSONObject("SessionInfo");
        return si == null ? "" : si.optString("Name", "");
    }

    public synchronized String sessionStatus() {
        JSONObject si = state.optJSONObject("SessionInfo");
        String s = si == null ? "" : si.optString("SessionStatus", "");
        if (s.length() > 0) {
            return s;
        }
        JSONObject ss = state.optJSONObject("SessionStatus");
        return ss == null ? "" : ss.optString("Status", "");
    }

    /** 环节剩余时间，例如 "00:12:34"。 */
    public synchronized String remaining() {
        JSONObject c = state.optJSONObject("ExtrapolatedClock");
        return c == null ? "" : c.optString("Remaining", "");
    }

    public synchronized String airTemp() {
        JSONObject w = state.optJSONObject("WeatherData");
        return w == null ? "" : w.optString("AirTemp", "");
    }

    public synchronized String trackTemp() {
        JSONObject w = state.optJSONObject("WeatherData");
        return w == null ? "" : w.optString("TrackTemp", "");
    }

    // ------------------------------------------------------------------
    // 车手 / 轮胎 / 进站
    // ------------------------------------------------------------------

    /** 一位车手在本场的位置、轮胎与进站情况。 */
    public static class Car {
        public String number = "";
        public String tla = "";
        public String team = "";
        public String teamColour = "";
        public String fullName = "";
        /** 赛道位置，1 起；0 = 未知（还没跑过计时）。 */
        public int position = 0;
        public boolean inPit = false;
        public boolean retired = false;
        /** 当前轮胎配方：SOFT / MEDIUM / HARD / INTERMEDIATE / WET。 */
        public String compound = "";
        /** 当前这套胎已经跑了多少圈。 */
        public int tyreLaps = 0;
        /** 第几套胎（1 起）。 */
        public int stint = 0;
        /** 进站次数 = 套数 - 1。 */
        public int pitStops = 0;
        /** 与领先者差距，例如 "+12.345" 或 "1 LAP"。 */
        public String gap = "";
        /** 与前车的间隔，例如 "+1.234"。 */
        public String interval = "";
        /** 个人最快圈，例如 "1:38.220"。 */
        public String bestLap = "";

        /** 供界面显示：有 TLA 用 TLA，否则用车号。 */
        public String label() {
            return tla.length() > 0 ? tla : number;
        }
    }

    /**
     * 当前全部车手，**按赛道位置排序**（未知的排最后，按车号）。
     *
     * 用户要求「按 22 个位置预留、顺序按赛道位置」，所以这里排好给界面直接用。
     */
    public synchronized List<Car> cars() {
        Map<String, Car> map = new HashMap<String, Car>();
        JSONObject dl = state.optJSONObject("DriverList");
        if (dl != null) {
            Iterator<String> it = dl.keys();
            while (it.hasNext()) {
                String no = it.next();
                JSONObject d = dl.optJSONObject(no);
                if (d == null) {
                    continue;
                }
                Car c = car(map, no);
                c.tla = d.optString("Tla", c.tla);
                c.team = d.optString("TeamName", c.team);
                c.teamColour = d.optString("TeamColour", c.teamColour);
                c.fullName = d.optString("FullName", c.fullName);
            }
        }

        JSONObject td = state.optJSONObject("TimingData");
        JSONObject tdLines = td == null ? null : td.optJSONObject("Lines");
        if (tdLines != null) {
            Iterator<String> it = tdLines.keys();
            while (it.hasNext()) {
                String no = it.next();
                JSONObject l = tdLines.optJSONObject(no);
                if (l == null) {
                    continue;
                }
                Car c = car(map, no);
                int p = 0;
                try {
                    p = Integer.parseInt(l.optString("Position", "0").trim());
                } catch (Exception ignored) {
                    // 位置可能是空串（还没定序）
                }
                c.position = p;
                c.inPit = l.optBoolean("InPit", c.inPit);
                c.retired = l.optBoolean("Retired", c.retired);
                c.gap = l.optString("GapToLeader", c.gap);
                JSONObject iv = l.optJSONObject("IntervalToPositionAhead");
                if (iv != null) {
                    c.interval = iv.optString("Value", c.interval);
                }
            }
        }

        JSONObject ad = state.optJSONObject("TimingAppData");
        JSONObject adLines = ad == null ? null : ad.optJSONObject("Lines");
        if (adLines != null) {
            Iterator<String> it = adLines.keys();
            while (it.hasNext()) {
                String no = it.next();
                JSONObject l = adLines.optJSONObject(no);
                if (l == null) {
                    continue;
                }
                // ★ `Stints` 是**数组**，不是以“第几套”为键的对象：
                //     "Stints": [ {"Compound":"INTERMEDIATE","TotalLaps":9}, {...} ]
                //   原来写成 optJSONObject("Stints") 直接拿到 null 就 continue，
                //   结果轮胎配方和圈数一直是空的（冒烟测试里表现为「胎=(0圈)」）。
                //   两种形状都兼容：数组取最后一个，对象取键最大的那个。
                JSONObject curStint = null;
                int stintCount = 0;
                JSONArray stintArr = l.optJSONArray("Stints");
                if (stintArr != null) {
                    stintCount = stintArr.length();
                    if (stintCount > 0) {
                        curStint = stintArr.optJSONObject(stintCount - 1);
                    }
                } else {
                    JSONObject stintMap = l.optJSONObject("Stints");
                    if (stintMap != null) {
                        Iterator<String> sit = stintMap.keys();
                        while (sit.hasNext()) {
                            String k = sit.next();
                            int n = 0;
                            try {
                                n = Integer.parseInt(k.trim());
                            } catch (Exception ignored) {
                                continue;
                            }
                            if (n >= stintCount) {
                                stintCount = n;
                                curStint = stintMap.optJSONObject(k);
                            }
                        }
                    }
                }
                if (curStint == null) {
                    continue;
                }
                Car c = car(map, no);
                c.stint = stintCount;
                c.pitStops = stintCount > 0 ? stintCount - 1 : 0;
                String comp = curStint.optString("Compound", "");
                if (comp.length() > 0) {
                    c.compound = comp;
                }
                c.tyreLaps = curStint.optInt("TotalLaps", c.tyreLaps);
            }
        }

        JSONObject stats = state.optJSONObject("TimingStats");
        JSONObject stLines = stats == null ? null : stats.optJSONObject("Lines");
        if (stLines != null) {
            Iterator<String> it = stLines.keys();
            while (it.hasNext()) {
                String no = it.next();
                JSONObject l = stLines.optJSONObject(no);
                JSONObject pb = l == null ? null
                        : l.optJSONObject("PersonalBestLapTime");
                if (pb == null) {
                    continue;
                }
                Car c = car(map, no);
                c.bestLap = pb.optString("Value", c.bestLap);
            }
        }

        List<Car> out = new ArrayList<Car>(map.values());
        Collections.sort(out, new Comparator<Car>() {
            public int compare(Car a, Car b) {
                int pa = a.position > 0 ? a.position : 9999;
                int pb = b.position > 0 ? b.position : 9999;
                if (pa != pb) {
                    return pa - pb;
                }
                return a.number.compareTo(b.number);
            }
        });
        return out;
    }

    private static Car car(Map<String, Car> map, String no) {
        Car c = map.get(no);
        if (c == null) {
            c = new Car();
            c.number = no;
            map.put(no, c);
        }
        return c;
    }

    /**
     * 本场出现过的最大区段号 —— 圆环按它分配段数。
     *
     * 为什么不做成固定表：官方**没有任何文件写出这个数**
     * （FIA 每站 55 份文档全查过，只有赛道图上画着编号）。
     * 而区段号只会随消息出现，所以"出现过多少就是多少"最稳，
     * 也不用维护 24 站的表。返回 0 表示本场还没有过区段消息。
     */
    public synchronized int sectorCount() {
        return maxSector;
    }

    /**
     * 前三名 + 圈速，一行一个人（TopThree 流）。
     *
     * <p>★ `Lines` 有**两种形状**，而且真实数据里默认给的是后者：
     * <pre>
     *   [ {"Position":"1","Tla":"VER","LapTime":"1:38.220"}, … ]     数组
     *   { "0": {"Tla":"VER","LapTime":"1:38.220"}, "1": {…} }        以名次为键的对象
     * </pre>
     * 原来只认数组（`optJSONArray`），对着对象直接返回 null，于是
     * **前三名一直是空的** —— 和 `Stints` 是同一类坑，而且更难发现：
     * "面板空着"和"这节比赛还没跑到"长得一模一样。
     */
    public synchronized java.util.List<String> topThree() {
        java.util.List<String> out = new java.util.ArrayList<String>();
        JSONObject tt = state.optJSONObject("TopThree");
        if (tt == null) {
            return out;
        }
        JSONArray lines = tt.optJSONArray("Lines");
        if (lines != null) {
            for (int i = 0; i < lines.length(); i++) {
                JSONObject o = lines.optJSONObject(i);
                if (o != null) {
                    out.add(topLine(o, o.optString("Position", "?")));
                }
            }
            return out;
        }
        JSONObject map = tt.optJSONObject("Lines");
        if (map != null) {
            // 键就是名次（"0" = P1），按数值排一遍，别靠字典序
            java.util.TreeMap<Integer, JSONObject> sorted =
                    new java.util.TreeMap<Integer, JSONObject>();
            Iterator<String> it = map.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONObject o = map.optJSONObject(k);
                if (o == null) {
                    continue;
                }
                int pos = 0;
                try {
                    pos = Integer.parseInt(k.trim());
                } catch (Exception ignored) {
                    pos = 0;                 // 不是数字就都排到最前面，至少不丢
                }
                sorted.put(Integer.valueOf(pos), o);
            }
            Iterator<Integer> si = sorted.keySet().iterator();
            while (si.hasNext()) {
                Integer k = si.next();
                out.add(topLine(sorted.get(k),
                        String.valueOf(k.intValue() + 1)));
            }
        }
        return out;
    }

    /** 前三名里的一行。"P1 VER  1:38.220"。 */
    private static String topLine(JSONObject o, String pos) {
        String p = pos == null || pos.length() == 0
                ? o.optString("Position", "?") : pos;
        String tla = o.optString("Tla", o.optString("BroadcastName", ""));
        String lap = o.optString("LapTime", "");
        return "P" + p + " " + tla + (lap.length() > 0 ? "  " + lap : "");
    }

    /** 天气一行字：气温 / 赛道温 / 湿度 / 风 / 降雨。 */
    public synchronized String weatherText() {
        JSONObject w = state.optJSONObject("WeatherData");
        if (w == null) {
            return "";
        }
        return "气温 " + w.optString("AirTemp", "?") + "°C"
                + "  赛道 " + w.optString("TrackTemp", "?") + "°C"
                + "  湿度 " + w.optString("Humidity", "?") + "%"
                + "  风 " + w.optString("WindSpeed", "?") + " m/s"
                + (isRaining(w) ? "  ☂有雨" : "");
    }

    private static boolean isRaining(JSONObject w) {
        String r = w.optString("Rainfall", "0");
        try {
            return Double.parseDouble(r) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 轮胎配方的中文名。 */
    public static String compoundCn(String en) {
        if (en == null) {
            return "";
        }
        String s = en.trim().toUpperCase(Locale.US);
        if ("SOFT".equals(s)) {
            return "软";
        }
        if ("MEDIUM".equals(s)) {
            return "中";
        }
        if ("HARD".equals(s)) {
            return "硬";
        }
        if ("INTERMEDIATE".equals(s)) {
            return "中性";
        }
        if ("WET".equals(s)) {
            return "全雨";
        }
        return en;
    }
}
