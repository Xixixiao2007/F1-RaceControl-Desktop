package com.haf1.racecontrol.desktop;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 极简 JSON 输出器。
 *
 * ## 为什么不用 org.json 自己的 toString()
 * 桌面跑的是安卓仓库 {@code tools/tztest/stub} 下的 {@code org.json} ——
 * 那是给**单测解析**用的桩，只实现了读（opt 系列、keys、length），**没有 toString()**。
 * 我们不改那个桩：它是安卓仓库的测试基建，改了就和安卓那边漂移了。
 * 所以序列化放在桌面这侧，只用桩的**公开读接口**递归遍历。
 *
 * 桩内部把对象存成 {@code java.util.Map}、数组存成 {@code java.util.List}，
 * 所以除了 JSONObject/JSONArray 还要认这两种原生容器 ——
 * 嵌套值从 {@code opt()} 拿出来时可能就是裸的 Map/List。
 */
public final class Json {

    private Json() {
    }

    public static String of(Object v) {
        StringBuilder b = new StringBuilder(1 << 12);
        write(b, v);
        return b.toString();
    }

    public static void write(StringBuilder b, Object v) {
        if (v == null) {
            b.append("null");
        } else if (v instanceof JSONObject) {
            writeObject(b, (JSONObject) v);
        } else if (v instanceof JSONArray) {
            writeArray(b, (JSONArray) v);
        } else if (v instanceof Map) {
            writeMap(b, (Map<?, ?>) v);
        } else if (v instanceof List) {
            writeList(b, (List<?>) v);
        } else if (v instanceof String) {
            quote(b, (String) v);
        } else if (v instanceof Boolean) {
            b.append(((Boolean) v).booleanValue() ? "true" : "false");
        } else if (v instanceof Number) {
            writeNumber(b, (Number) v);
        } else {
            quote(b, String.valueOf(v));
        }
    }

    private static void writeObject(StringBuilder b, JSONObject o) {
        b.append('{');
        Iterator<String> it = o.keys();
        boolean first = true;
        while (it.hasNext()) {
            String k = it.next();
            if (!first) {
                b.append(',');
            }
            first = false;
            quote(b, k);
            b.append(':');
            write(b, o.opt(k));
        }
        b.append('}');
    }

    private static void writeArray(StringBuilder b, JSONArray a) {
        b.append('[');
        int n = a.length();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                b.append(',');
            }
            write(b, a.opt(i));
        }
        b.append(']');
    }

    private static void writeMap(StringBuilder b, Map<?, ?> m) {
        b.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!first) {
                b.append(',');
            }
            first = false;
            quote(b, String.valueOf(e.getKey()));
            b.append(':');
            write(b, e.getValue());
        }
        b.append('}');
    }

    private static void writeList(StringBuilder b, List<?> l) {
        b.append('[');
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            write(b, l.get(i));
        }
        b.append(']');
    }

    /**
     * 数字。★ 整数值的 Double 必须写成 {@code 55} 而不是 {@code 55.0} ——
     * F1 流里圈数、位置、区段号都可能是 Double，前端按整数用（比较、下标）。
     */
    private static void writeNumber(StringBuilder b, Number n) {
        if (n instanceof Double || n instanceof Float) {
            double d = n.doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d)
                    && Math.abs(d) < 1e15) {
                b.append((long) d);
                return;
            }
            b.append(d);
            return;
        }
        b.append(n.toString());
    }

    /** 字符串转义。U+2028/2029 也转 —— 它们会打断 JS 的字面量。 */
    public static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    b.append("\\\"");
                    break;
                case '\\':
                    b.append("\\\\");
                    break;
                case '\n':
                    b.append("\\n");
                    break;
                case '\r':
                    b.append("\\r");
                    break;
                case '\t':
                    b.append("\\t");
                    break;
                case '\b':
                    b.append("\\b");
                    break;
                case '\f':
                    b.append("\\f");
                    break;
                default:
                    if (c < 0x20 || c == '\u2028' || c == '\u2029') {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        b.append('"');
    }

    // ------------------------------------------------------------------
    // 拼装我们自己的输出（不想为这个引入一个 JSON 库）
    // ------------------------------------------------------------------

    public static final class Obj {
        private final StringBuilder b = new StringBuilder(256);
        private boolean first = true;

        public Obj() {
            b.append('{');
        }

        private Obj key(String k) {
            if (!first) {
                b.append(',');
            }
            first = false;
            quote(b, k);
            b.append(':');
            return this;
        }

        public Obj put(String k, String v) {
            key(k);
            if (v == null) {
                b.append("null");
            } else {
                quote(b, v);
            }
            return this;
        }

        public Obj put(String k, long v) {
            key(k).b.append(v);
            return this;
        }

        public Obj put(String k, double v) {
            key(k);
            writeNumber(b, Double.valueOf(v));
            return this;
        }

        public Obj put(String k, boolean v) {
            key(k).b.append(v ? "true" : "false");
            return this;
        }

        /** 原始 JSON 片段（已经是合法 JSON 的字符串），不加引号。 */
        public Obj raw(String k, String json) {
            key(k);
            b.append(json == null ? "null" : json);
            return this;
        }

        public Obj put(String k, Object v) {
            key(k);
            write(b, v);
            return this;
        }

        public String done() {
            b.append('}');
            return b.toString();
        }
    }

    public static final class Arr {
        private final StringBuilder b = new StringBuilder(256);
        private boolean first = true;

        public Arr() {
            b.append('[');
        }

        public Arr add(Object v) {
            if (!first) {
                b.append(',');
            }
            first = false;
            write(b, v);
            return this;
        }

        /** 原始 JSON 片段。 */
        public Arr raw(String json) {
            if (!first) {
                b.append(',');
            }
            first = false;
            b.append(json == null ? "null" : json);
            return this;
        }

        public Arr addStrings(List<String> list) {
            for (int i = 0; i < list.size(); i++) {
                add(list.get(i));
            }
            return this;
        }

        public Arr addInts(List<Integer> list) {
            for (int i = 0; i < list.size(); i++) {
                add(list.get(i));
            }
            return this;
        }

        public Arr addFloats(float[] a) {
            for (int i = 0; a != null && i < a.length; i++) {
                add(Double.valueOf(a[i]));
            }
            return this;
        }

        public String done() {
            b.append(']');
            return b.toString();
        }
    }
}