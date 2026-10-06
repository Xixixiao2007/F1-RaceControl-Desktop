package org.json;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 桌面测试专用的 JSONObject —— 是**真的解析**，不是空桩。
 * 见 {@link JSONTokener} 里关于"为什么不能用空桩"的说明。
 */
public class JSONObject {

    private final Map<String, Object> map;

    public JSONObject() {
        this.map = new LinkedHashMap<String, Object>();
    }

    public JSONObject(String source) throws JSONException {
        Object o = new JSONTokener(source).nextValue();
        if (!(o instanceof Map)) {
            throw new JSONException("顶层不是 JSON 对象");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) o;
        this.map = m;
    }

    JSONObject(Map<String, Object> m) {
        this.map = m;
    }

    public boolean has(String name) {
        return map.containsKey(name);
    }

    public Object opt(String name) {
        return map.get(name);
    }

    public String optString(String name) {
        Object v = map.get(name);
        return v == null ? "" : String.valueOf(v);
    }

    public String optString(String name, String fallback) {
        Object v = map.get(name);
        return v == null ? fallback : String.valueOf(v);
    }

    public int optInt(String name, int fallback) {
        Object v = map.get(name);
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        if (v instanceof String) {
            try {
                return Integer.parseInt(((String) v).trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public long optLong(String name, long fallback) {
        Object v = map.get(name);
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        if (v instanceof String) {
            try {
                return Long.parseLong(((String) v).trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public double optDouble(String name, double fallback) {
        Object v = map.get(name);
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        return fallback;
    }

    public boolean optBoolean(String name) {
        return optBoolean(name, false);
    }

    public boolean optBoolean(String name, boolean fallback) {
        Object v = map.get(name);
        if (v instanceof Boolean) {
            return ((Boolean) v).booleanValue();
        }
        if (v instanceof String) {
            String s = ((String) v).trim();
            if ("true".equalsIgnoreCase(s)) {
                return true;
            }
            if ("false".equalsIgnoreCase(s)) {
                return false;
            }
        }
        return fallback;
    }

    public JSONObject optJSONObject(String name) {
        Object v = map.get(name);
        // ★ 两种都要认：真实 org.json 允许把一个 JSONObject 直接 put 进去，
        //   而解析器产生的是原生 Map。只认 Map 的话，自己 put 进去的嵌套对象
        //   再 optJSONObject 就会拿到 null —— F1Feed 的 SessionInfo 一度
        //   就是这么"存进去了却取不出来"的。
        if (v instanceof JSONObject) {
            return (JSONObject) v;
        }
        if (v instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) v;
            return new JSONObject(m);
        }
        return null;
    }

    public JSONArray optJSONArray(String name) {
        Object v = map.get(name);
        if (v instanceof JSONArray) {
            return (JSONArray) v;
        }
        if (v instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> l = (List<Object>) v;
            return new JSONArray(l);
        }
        return null;
    }

    public JSONObject put(String name, Object value) throws JSONException {
        map.put(name, value);
        return this;
    }

    public Object get(String name) throws JSONException {
        Object v = map.get(name);
        if (v == null) {
            throw new JSONException("\u4e0d\u5b58\u5728\u7684\u952e: " + name);
        }
        return v;
    }

    /**
     * \u771f\u5b9e org.json \u91cc\u201c\u4e0d\u5b58\u5728\u201d\u548c\u201c\u503c\u662f null\u201d
     * \u90fd\u7b97 isNull\u3002F1 \u7684 Sector \u5b57\u6bb5\u5c31\u4f1a\u51fa\u73b0\u540e\u8005\u3002
     */
    public boolean isNull(String name) {
        return !map.containsKey(name) || map.get(name) == null;
    }

    /** \u904d\u5386\u952e\u540d\uff08\u4fdd\u6301\u63d2\u5165\u987a\u5e8f\uff09\u3002 */
    public java.util.Iterator<String> keys() {
        return map.keySet().iterator();
    }
}
