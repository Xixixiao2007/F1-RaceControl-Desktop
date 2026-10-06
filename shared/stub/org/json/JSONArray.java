package org.json;

import java.util.List;

/**
 * 桌面测试专用的 JSONArray —— 是**真的解析**，不是空桩。
 * 见 {@link JSONTokener} 里关于"为什么不能用空桩"的说明。
 */
public class JSONArray {

    private final List<Object> list;

    public JSONArray(String source) throws JSONException {
        Object o = new JSONTokener(source).nextValue();
        if (!(o instanceof List)) {
            throw new JSONException("顶层不是 JSON 数组");
        }
        @SuppressWarnings("unchecked")
        List<Object> l = (List<Object>) o;
        this.list = l;
    }

    JSONArray(List<Object> l) {
        this.list = l;
    }

    public int length() {
        return list.size();
    }

    public Object opt(int index) {
        if (index < 0 || index >= list.size()) {
            return null;
        }
        return list.get(index);
    }

    public JSONObject optJSONObject(int index) {
        Object v = opt(index);
        if (v instanceof java.util.Map) {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> m = (java.util.Map<String, Object>) v;
            return new JSONObject(m);
        }
        return null;
    }

    public JSONArray optJSONArray(int index) {
        Object v = opt(index);
        if (v instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> l = (List<Object>) v;
            return new JSONArray(l);
        }
        return null;
    }

    public String optString(int index) {
        Object v = opt(index);
        return v == null ? "" : String.valueOf(v);
    }
}
