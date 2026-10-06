package org.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析器 —— **桌面测试专用**，不是 Android 的那份实现。
 *
 * 为什么不能用"返回兜底值"的空桩：集成测试要让 HaClient / HaWebSocket
 * 去解析真实服务器返回的报文。空桩会让它们永远解析出空结果，
 * 于是测试全绿但什么都没验证 —— 那比没有测试更糟。
 *
 * 只实现本项目用得到的部分：对象、数组、字符串、数字、布尔、null。
 */
public class JSONTokener {

    private final String src;
    private int pos;

    public JSONTokener(String s) {
        this.src = s == null ? "" : s;
        this.pos = 0;
    }

    public Object nextValue() throws JSONException {
        skipWs();
        if (pos >= src.length()) {
            throw new JSONException("输入为空");
        }
        char c = src.charAt(pos);
        if (c == '{') {
            return readObject();
        }
        if (c == '[') {
            return readArray();
        }
        if (c == '"') {
            return readString();
        }
        if (src.startsWith("true", pos)) {
            pos += 4;
            return Boolean.TRUE;
        }
        if (src.startsWith("false", pos)) {
            pos += 5;
            return Boolean.FALSE;
        }
        if (src.startsWith("null", pos)) {
            pos += 4;
            return null;
        }
        return readNumber();
    }

    private Map<String, Object> readObject() throws JSONException {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        pos++;                                  // 跳过 '{'
        skipWs();
        if (pos < src.length() && src.charAt(pos) == '}') {
            pos++;
            return m;
        }
        while (true) {
            skipWs();
            if (pos >= src.length() || src.charAt(pos) != '"') {
                throw new JSONException("对象键必须是字符串，位置 " + pos);
            }
            String k = readString();
            skipWs();
            if (pos >= src.length() || src.charAt(pos) != ':') {
                throw new JSONException("键后面缺冒号，位置 " + pos);
            }
            pos++;
            m.put(k, nextValue());
            skipWs();
            if (pos >= src.length()) {
                throw new JSONException("对象没有闭合");
            }
            char c = src.charAt(pos);
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == '}') {
                pos++;
                return m;
            }
            throw new JSONException("对象里出现意外字符 " + c + "，位置 " + pos);
        }
    }

    private List<Object> readArray() throws JSONException {
        List<Object> l = new ArrayList<Object>();
        pos++;                                  // 跳过 '['
        skipWs();
        if (pos < src.length() && src.charAt(pos) == ']') {
            pos++;
            return l;
        }
        while (true) {
            l.add(nextValue());
            skipWs();
            if (pos >= src.length()) {
                throw new JSONException("数组没有闭合");
            }
            char c = src.charAt(pos);
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == ']') {
                pos++;
                return l;
            }
            throw new JSONException("数组里出现意外字符 " + c + "，位置 " + pos);
        }
    }

    private String readString() throws JSONException {
        StringBuilder b = new StringBuilder();
        pos++;                                  // 跳过起始引号
        while (true) {
            if (pos >= src.length()) {
                throw new JSONException("字符串没有闭合");
            }
            char c = src.charAt(pos++);
            if (c == '"') {
                return b.toString();
            }
            if (c != '\\') {
                b.append(c);
                continue;
            }
            if (pos >= src.length()) {
                throw new JSONException("转义符后没有字符");
            }
            char e = src.charAt(pos++);
            switch (e) {
                case '"':  b.append('"');  break;
                case '\\': b.append('\\'); break;
                case '/':  b.append('/');  break;
                case 'b':  b.append('\b'); break;
                case 'f':  b.append('\f'); break;
                case 'n':  b.append('\n'); break;
                case 'r':  b.append('\r'); break;
                case 't':  b.append('\t'); break;
                case 'u':
                    if (pos + 4 > src.length()) {
                        throw new JSONException("\\u 后面不足 4 位");
                    }
                    b.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                    pos += 4;
                    break;
                default:
                    throw new JSONException("不认识的转义 \\" + e);
            }
        }
    }

    private Object readNumber() throws JSONException {
        int start = pos;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.'
                    || c == 'e' || c == 'E') {
                pos++;
            } else {
                break;
            }
        }
        String num = src.substring(start, pos);
        if (num.length() == 0) {
            throw new JSONException("不是合法 JSON，位置 " + start);
        }
        try {
            if (num.indexOf('.') < 0 && num.indexOf('e') < 0 && num.indexOf('E') < 0) {
                return Long.valueOf(Long.parseLong(num));
            }
            return Double.valueOf(Double.parseDouble(num));
        } catch (NumberFormatException ex) {
            throw new JSONException("数字解析失败：" + num);
        }
    }

    private void skipWs() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }
}
