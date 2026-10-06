package com.haf1.racecontrol;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Home Assistant REST 客户端。
 *
 * 只用 java.net.HttpURLConnection + org.json（framework 自带），零第三方依赖。
 * 关键接口: GET /api/history/period/<start>?filter_entity_id=<id>&minimal_response&no_attributes
 */
public class HaClient {

    /** 带中文提示的异常。 */
    public static class HaException extends Exception {
        public final int code;

        public HaException(int code, String msg) {
            super(msg);
            this.code = code;
        }
    }

    // ------------------------------------------------------------------
    // 底层 HTTP
    // ------------------------------------------------------------------

    private static String get(String url, String token, int connectMs, int readMs) throws HaException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(connectMs);
            conn.setReadTimeout(readMs);
            conn.setUseCaches(false);

            int code = conn.getResponseCode();
            if (code == 200) {
                return readAll(conn.getInputStream());
            }
            String body = "";
            try {
                InputStream es = conn.getErrorStream();
                if (es != null) {
                    body = readAll(es);
                }
            } catch (Exception ignored) {
                // 读错误体失败不影响主流程
            }
            throw new HaException(code, explain(code, body));
        } catch (HaException e) {
            throw e;
        } catch (Exception e) {
            String m = e.getMessage();
            if (m == null || m.length() == 0) {
                m = e.getClass().getSimpleName();
            }
            throw new HaException(-1, "连接失败：" + m);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 把 HTTP 状态码翻译成用户能看懂的中文。 */
    private static String explain(int code, String body) {
        if (code == 401) {
            // 注意：HA 对「非管理员访问管理员专属接口」也返回 401，
            // 很容易被误判成令牌写错了。这里的提示把这层歧义讲清楚。
            return "401 未授权：令牌错误、已失效，或该用户没有此实体的读取权限"
                    + "（HA 对权限不足也返回 401 而非 403）";
        }
        if (code == 403) {
            return "403 禁止访问：该用户权限不足";
        }
        if (code == 404) {
            return "404 找不到：实体 ID 可能写错了";
        }
        String tail = body == null ? "" : body.trim();
        if (tail.length() > 160) {
            tail = tail.substring(0, 160) + "…";
        }
        return "HTTP " + code + (tail.length() > 0 ? "：" + tail : "");
    }

    private static String readAll(InputStream in) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) {
            sb.append(line).append('\n');
        }
        r.close();
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 业务接口
    // ------------------------------------------------------------------

    /** 连通性 + 令牌自检。返回给用户看的一句话。 */
    public static String testConnection(Prefs p) throws HaException {
        String body = get(p.baseUrl() + "/api/", p.token, 6000, 10000);
        String msg = "已连上 HA";
        try {
            JSONObject o = new JSONObject(body);
            String m = o.optString("message", "");
            if (m.length() > 0) {
                msg = "已连上 HA（" + m + "）";
            }
        } catch (Exception ignored) {
            // 只要 200 就算通
        }
        if (p.entityId != null && p.entityId.trim().length() > 0) {
            String st = get(p.baseUrl() + "/api/states/" + enc(p.entityId.trim()), p.token, 6000, 10000);
            try {
                JSONObject o = new JSONObject(st);
                String name = o.optJSONObject("attributes") == null
                        ? "" : o.optJSONObject("attributes").optString("friendly_name", "");
                String cur = o.optString("state", "");
                msg += "\n实体：" + (name.length() > 0 ? name + "  " : "") + "当前值 = " + cur;
            } catch (Exception ignored) {
                msg += "\n实体已找到";
            }
        }
        return msg;
    }

    /**
     * 拉取 [startMillis, endMillis] 区间内该实体的状态变化。
     *
     * HA 返回的是「数组的数组」：
     *   [[ {"state":"24.9","last_changed":"2026-09-05T14:20:12+08:00"}, ... ]]
     * minimal_response 下只有首个和末个条目带完整字段，中间只有 state + last_changed。
     *
     * ⚠️ **必须丢掉响应里的首个条目**：那是 HA 合成的「期初状态」（该区间开始时仍然生效
     * 的那个值），它的 last_changed 被设成了**我们请求的起始时刻**，而不是这个状态真正
     * 变更的时间。若不丢，它就会顶着「最新一条变化的时间」再出现一次 —— 这就是同一条
     * 消息显示两遍的原因（实测见 CHANGELOG v1.4）。
     */
    /**
     * 拉取 [startMillis, endMillis] 区间内该实体的状态变化。
     *
     * HA 返回的是「数组的数组」：
     *   [[ {"state":"...","last_changed":"...","attributes":{...}}, ... ]]
     *
     * ## 与上一代的两处关键差别
     *
     * 1. **不再带 `minimal_response` / `no_attributes`。**
     *    上一代只要 (时间, 值)，所以用这两个参数把响应压到最小。但本 App 的旗语分类
     *    依赖 `flag` / `category` / `sector` 这些属性，而 `minimal_response` 会把
     *    中间条目的属性全部抹掉（只有首末两条完整）。代价是响应体变大
     *    （每条还带 history 与 raw_message），所以默认回溯窗口从 24 小时缩到 12 小时。
     *
     * 2. **去重键改用 event_id**（见 {@link RaceMessage#key()}）。
     *
     * ⚠️ 仍然必须丢掉响应里的首个条目：那是 HA 合成的「期初状态」（该区间开始时仍然
     * 生效的那个值），它的 last_changed 被设成了**我们请求的起始时刻**，而不是这个状态
     * 真正变更的时间。若不丢，它就会顶着「最新一条变化的时间」再出现一次 —— 这就是
     * 同一条消息显示两遍的原因（实测见 CHANGELOG v1.4）。
     */
    public static List<RaceMessage> fetchHistory(Prefs p, long startMillis, long endMillis)
            throws HaException {
        String url = p.baseUrl()
                + "/api/history/period/" + enc(isoLocal(startMillis))
                + "?filter_entity_id=" + enc(p.entityId.trim())
                + "&end_time=" + enc(isoLocal(endMillis));

        String body = get(url, p.token, 8000, 25000);
        List<RaceMessage> out = new ArrayList<RaceMessage>();
        try {
            JSONArray outer = new JSONArray(body);
            if (outer.length() == 0) {
                return out;
            }
            JSONArray inner = outer.optJSONArray(0);
            if (inner == null) {
                return out;
            }
            for (int i = 0; i < inner.length(); i++) {
                JSONObject o = inner.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                RaceMessage m = RaceMessage.parse(o);
                if (m == null) {
                    continue;
                }
                if (isSyntheticStartState(i, m.time, startMillis, o.has("entity_id"))) {
                    continue;
                }
                if (m.isUnavailable()) {
                    continue;
                }
                out.add(m);
            }
        } catch (Exception e) {
            throw new HaException(-2, "返回内容无法解析：" + e.getMessage());
        }
        return out;
    }

    /**
     * 判断某个历史条目是不是 HA 合成的「期初状态」。
     *
     * 三条判据同时满足才算（纯函数，便于单测）：
     *   1. 是响应里的第一条；
     *   2. 带完整字段（有 entity_id）—— minimal_response 下只有期初状态和末条带完整字段；
     *   3. 它的时间戳正好落在我们请求的起始秒上。
     *
     * 末条同样带完整字段，但它是真实变化，靠第 1 条判据排除。
     */
    static boolean isSyntheticStartState(int index, long entryTimeMillis,
                                         long startMillis, boolean hasEntityId) {
        return index == 0
                && hasEntityId
                && (entryTimeMillis / 1000L) == (startMillis / 1000L);
    }

    // ------------------------------------------------------------------
    // 时间处理
    // ------------------------------------------------------------------

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String two(int v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }

    /**
     * 生成本地时区的 ISO8601（带偏移量），例如 2026-09-05T14:23:05+08:00。
     * 不用 SimpleDateFormat 的 X/XXX 模式 —— 那是 API 24 才支持的，Android 6 上会抛异常。
     */
    static String isoLocal(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
        String base = f.format(new Date(ms));
        int off = TimeZone.getDefault().getOffset(ms);
        int h = Math.abs(off) / 3600000;
        int m = (Math.abs(off) % 3600000) / 60000;
        return base + (off >= 0 ? "+" : "-") + two(h) + ":" + two(m);
    }

    /**
     * 解析 HA 返回的 ISO8601，例如 2026-09-05T14:23:05.123456+08:00 或 ...Z。
     * 手写解析：Android 6 的 SimpleDateFormat 不支持 X/XXX 时区模式。
     */
    static long parseIso(String raw) {
        if (raw == null) {
            return 0;
        }
        String t = raw.trim();
        String offset = "+00:00";
        if (t.endsWith("Z") || t.endsWith("z")) {
            t = t.substring(0, t.length() - 1);
        } else {
            int i = -1;
            for (int k = t.length() - 1; k > 10; k--) {
                char ch = t.charAt(k);
                if (ch == '+' || ch == '-') {
                    i = k;
                    break;
                }
            }
            if (i > 0) {
                offset = t.substring(i);
                t = t.substring(0, i);
            }
        }
        // 小数秒必须保留到毫秒：HA 的 last_changed 精确到微秒，
        // 丢掉它会让同一秒内的多次变化拿到相同的时间戳。
        long fracMs = 0;
        int dot = t.indexOf('.');
        if (dot > 0) {
            String frac = t.substring(dot + 1);
            StringBuilder digits = new StringBuilder();
            for (int i = 0; i < frac.length() && digits.length() < 3; i++) {
                char c = frac.charAt(i);
                if (c < '0' || c > '9') {
                    break;
                }
                digits.append(c);
            }
            while (digits.length() < 3) {
                digits.append('0');
            }
            if (digits.length() > 0) {
                try {
                    fracMs = Long.parseLong(digits.toString());
                } catch (Exception ignored) {
                    fracMs = 0;
                }
            }
            t = t.substring(0, dot);
        }
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            long ms = f.parse(t).getTime() + fracMs;
            int sign = offset.charAt(0) == '-' ? -1 : 1;
            int oh = Integer.parseInt(offset.substring(1, 3));
            int om = Integer.parseInt(offset.substring(4, 6));
            ms -= sign * (oh * 3600000L + om * 60000L);
            return ms;
        } catch (Exception e) {
            return 0;
        }
    }
}
