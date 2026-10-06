package android.util;

/**
 * 仅供桌面端集成测试使用的极简桩，不是真实实现。
 *
 * HaWebSocket 用它生成 Sec-WebSocket-Key（16 字节随机数 -> base64）。
 * 桌面 JDK 自带 java.util.Base64，直接转发即可。
 */
public class Base64 {
    public static final int DEFAULT = 0;
    public static final int NO_PADDING = 1;
    public static final int NO_WRAP = 2;
    public static final int URL_SAFE = 8;

    public static String encodeToString(byte[] input, int flags) {
        if ((flags & NO_PADDING) != 0) {
            return java.util.Base64.getEncoder().withoutPadding().encodeToString(input);
        }
        return java.util.Base64.getEncoder().encodeToString(input);
    }

    public static byte[] encode(byte[] input, int flags) {
        return encodeToString(input, flags).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    public static byte[] decode(String str, int flags) {
        return java.util.Base64.getDecoder().decode(str);
    }
}
