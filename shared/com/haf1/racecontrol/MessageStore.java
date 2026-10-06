package com.haf1.racecontrol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 消息仓库：去重、排序、容量控制、落盘序列化。
 *
 * 去重键用 {@link RaceMessage#key()}（优先 event_id）。
 *
 * 序列化用自定义的制表符分隔格式而不是 JSON：消息里含大量中文与引号，
 * 自己转义比 JSON 库更可控，体积也小（500 条大约 60 KB，SharedPreferences 放得下）。
 */
public class MessageStore {

    private final LinkedHashMap<String, RaceMessage> map =
            new LinkedHashMap<String, RaceMessage>();
    private long newestTime = 0L;

    /** 加入一条；返回 true 表示这条是新的（用于决定要不要闪动/提醒）。 */
    public synchronized boolean add(RaceMessage m) {
        if (m == null || m.isUnavailable()) {
            return false;
        }
        String k = m.key();
        boolean isNew = !map.containsKey(k);
        map.put(k, m);
        if (m.time > newestTime) {
            newestTime = m.time;
        }
        return isNew;
    }

    public synchronized boolean contains(String key) {
        return map.containsKey(key);
    }

    public synchronized int size() {
        return map.size();
    }

    public synchronized long newestTime() {
        return newestTime;
    }

    public synchronized void clear() {
        map.clear();
        newestTime = 0L;
    }

    /** 按时间倒序（最新在最上）。 */
    public synchronized List<RaceMessage> sortedDesc() {
        List<RaceMessage> tmp = new ArrayList<RaceMessage>(map.values());
        Collections.sort(tmp, new Comparator<RaceMessage>() {
            public int compare(RaceMessage a, RaceMessage b) {
                if (a.time != b.time) {
                    return a.time > b.time ? -1 : 1;
                }
                return b.raw.compareTo(a.raw);
            }
        });
        return tmp;
    }

    /** 丢掉早于 cutoff 的记录。 */
    public synchronized void dropOlderThan(long cutoff) {
        Iterator<Map.Entry<String, RaceMessage>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().time < cutoff) {
                it.remove();
            }
        }
    }

    /** 容量上限，超出时丢掉最旧的。 */
    public synchronized void trimTo(int max) {
        if (max <= 0 || map.size() <= max) {
            return;
        }
        List<RaceMessage> asc = new ArrayList<RaceMessage>(map.values());
        Collections.sort(asc, new Comparator<RaceMessage>() {
            public int compare(RaceMessage a, RaceMessage b) {
                return a.time < b.time ? -1 : (a.time > b.time ? 1 : 0);
            }
        });
        int drop = map.size() - max;
        for (int i = 0; i < drop; i++) {
            map.remove(asc.get(i).key());
        }
    }

    // ------------------------------------------------------------------
    // 落盘
    // ------------------------------------------------------------------

    public synchronized String serialize() {
        StringBuilder sb = new StringBuilder();
        List<RaceMessage> asc = sortedDesc();
        // 反着写，读回来时插入顺序就是时间正序，便于渐进显示
        for (int i = asc.size() - 1; i >= 0; i--) {
            RaceMessage m = asc.get(i);
            sb.append(m.time).append('\t')
                    .append(esc(m.raw)).append('\t')
                    .append(esc(m.state)).append('\t')
                    .append(esc(m.message)).append('\t')
                    .append(esc(m.category)).append('\t')
                    .append(esc(m.flag)).append('\t')
                    .append(esc(m.scope)).append('\t')
                    .append(esc(m.sector)).append('\t')
                    .append(esc(m.carNumber)).append('\t')
                    .append(esc(m.utc)).append('\t')
                    .append(esc(m.eventId)).append('\t')
                    .append(m.sequence).append('\n');
        }
        return sb.toString();
    }

    public synchronized void load(String blob) {
        map.clear();
        newestTime = 0L;
        if (blob == null || blob.length() == 0) {
            return;
        }
        String[] lines = blob.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.length() == 0) {
                continue;
            }
            String[] p = line.split("\t", -1);
            if (p.length < 12) {
                continue;
            }
            try {
                RaceMessage m = new RaceMessage(
                        Long.parseLong(p[0]), unesc(p[2]), unesc(p[1]), unesc(p[3]),
                        unesc(p[4]), unesc(p[5]), unesc(p[6]), unesc(p[7]),
                        unesc(p[8]), unesc(p[9]), unesc(p[10]),
                        Integer.parseInt(p[11]));
                map.put(m.key(), m);
                if (m.time > newestTime) {
                    newestTime = m.time;
                }
            } catch (Exception ignored) {
                // 单行坏掉就跳过，不影响其余记录
            }
        }
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                b.append("\\\\");
            } else if (c == '\t') {
                b.append("\\t");
            } else if (c == '\n') {
                b.append("\\n");
            } else if (c == '\r') {
                b.append("\\r");
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    private static String unesc(String s) {
        if (s == null || s.indexOf('\\') < 0) {
            return s == null ? "" : s;
        }
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                if (n == 't') {
                    b.append('\t');
                } else if (n == 'n') {
                    b.append('\n');
                } else if (n == 'r') {
                    b.append('\r');
                } else {
                    b.append(n);
                }
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }
}
