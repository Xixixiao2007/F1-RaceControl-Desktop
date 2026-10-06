package android.content;

/** 仅供桌面端单元测试使用的极简桩，不是真实实现。 */
public class Context {
    public static final int MODE_PRIVATE = 0;

    public SharedPreferences getSharedPreferences(String name, int mode) {
        return null;
    }
}
