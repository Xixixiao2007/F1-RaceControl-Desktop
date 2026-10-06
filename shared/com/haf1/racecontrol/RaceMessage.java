package com.haf1.racecontrol;

import org.json.JSONObject;

/**
 * 一条赛事控制（Race Control）消息。
 *
 * 相比上一代只存 (时间, 值) 的 {@code Change}，这里保留了上游集成给出的
 * **结构化字段**：旗语分类靠它们判断，而不是靠猜消息文本。
 *
 * 实测确认的属性（见 dsh/files/data/racecontrol_history.tsv）：
 *   category  Flag / Other / SafetyCar
 *   flag      RED / DOUBLE YELLOW / YELLOW / CLEAR / BLUE / GREEN / CHEQUERED / BLACK AND WHITE
 *   scope     Track / Sector / Driver
 *   sector    1..26（字符串）
 *   car_number / event_id / sequence
 *
 * 所有字段都可能缺失：REST 历史接口在 minimal_response 下只给 state + last_changed，
 * 属性是空的。所以下游判断必须能容忍空值。
 */
public class RaceMessage {

    /** 变化时刻（epoch millis）。 */
    public final long time;
    /** 实体状态值（= 消息全文）。 */
    public final String state;
    /** HA 原始 last_changed 字符串，event_id 缺失时用作去重键。 */
    public final String raw;

    public final String message;
    public final String category;
    public final String flag;
    public final String scope;
    public final String sector;
    public final String carNumber;
    public final String utc;
    public final String eventId;
    public final int sequence;

    public RaceMessage(long time, String state, String raw, String message,
                       String category, String flag, String scope, String sector,
                       String carNumber, String utc, String eventId, int sequence) {
        this.time = time;
        this.state = state == null ? "" : state;
        this.raw = (raw == null || raw.length() == 0) ? String.valueOf(time) : raw;
        this.message = message == null ? "" : message;
        this.category = category == null ? "" : category;
        this.flag = flag == null ? "" : flag;
        this.scope = scope == null ? "" : scope;
        this.sector = sector == null ? "" : sector;
        this.carNumber = carNumber == null ? "" : carNumber;
        this.utc = utc == null ? "" : utc;
        this.eventId = eventId == null ? "" : eventId;
        this.sequence = sequence;
    }

    /** 只有 state + 时间的最小构造（历史接口 minimal_response 或桩数据用）。 */
    public RaceMessage(long time, String state, String raw) {
        this(time, state, raw, state, "", "", "", "", "", "", "", 0);
    }

    /**
     * 从 HA 的状态对象解析。同一个方法要吃三种输入：
     *   1. WebSocket 的 to_state（字段最全）
     *   2. REST 历史的完整条目
     *   3. REST 历史 minimal_response 的中间条目（只有 state / last_changed）
     * 所以每一项都要给默认值。
     */
    public static RaceMessage parse(JSONObject o) {
        if (o == null) {
            return null;
        }
        String raw = o.optString("last_changed", "");
        long t = HaClient.parseIso(raw);
        if (t <= 0) {
            return null;
        }
        String state = o.optString("state", "");
        JSONObject a = o.optJSONObject("attributes");
        if (a == null) {
            // minimal_response 的中间条目：只有 state，属性一概没有
            return new RaceMessage(t, state, raw);
        }
        return new RaceMessage(
                t, state, raw,
                a.optString("message", state),
                a.optString("category", ""),
                a.optString("flag", ""),
                a.optString("scope", ""),
                a.optString("sector", ""),
                a.optString("car_number", ""),
                a.optString("utc", ""),
                a.optString("event_id", ""),
                a.optInt("sequence", 0));
    }

    /**
     * 去重键：**优先用 event_id**。
     *
     * 上一代用 last_changed 字符串做键。改用 event_id 的理由：实测存在连续两条
     * **消息文本完全相同**的记录（09-11 23:58:55 / 23:58:58，09-13 21:26:26 / 21:26:50），
     * 靠文本+时间戳判定"是不是同一条"不可靠；而 event_id 是上游为每条消息生成的唯一 ID。
     * 没有 event_id 时退回原始 last_changed 字符串（上一代的方案，已验证可用）。
     */
    public String key() {
        return eventId.length() > 0 ? eventId : raw;
    }

    /** 用于显示和分类的文本：优先 attributes.message，退回 state。 */
    public String text() {
        return message.length() > 0 ? message : state;
    }

    /** 区段号，解析不出来返回 -1。 */
    public int sectorNo() {
        if (sector.length() == 0) {
            return -1;
        }
        try {
            return Integer.parseInt(sector.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 状态文本是否为"没有数据"。 */
    public boolean isUnavailable() {
        String s = state.trim().toLowerCase();
        return s.length() == 0 || "unavailable".equals(s)
                || "unknown".equals(s) || "none".equals(s);
    }

    @Override
    public String toString() {
        return "RaceMessage{" + raw + " flag=" + flag + " cat=" + category
                + " sec=" + sector + " " + text() + "}";
    }
}
