package android.content;

/** 仅供桌面端单元测试使用的极简桩，不是真实实现。 */
public interface SharedPreferences {
    String getString(String key, String def);

    int getInt(String key, int def);

    long getLong(String key, long def);

    boolean getBoolean(String key, boolean def);

    Editor edit();

    interface Editor {
        Editor putString(String key, String value);

        Editor putInt(String key, int value);

        Editor putLong(String key, long value);

        Editor putBoolean(String key, boolean value);

        void apply();
    }
}
