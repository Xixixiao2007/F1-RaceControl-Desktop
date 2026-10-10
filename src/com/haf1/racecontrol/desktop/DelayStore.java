package com.haf1.racecontrol.desktop;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import com.haf1.racecontrol.DelayGate;

/**
 * 延时设置的持久化 —— 就一个整数。
 *
 * 为什么要存：用户是**边看边调**的（不同直播源延迟不一样），挑好的值下次开机
 * 还得在，否则每次都得重新试。位置和看板窗口记忆同一个目录
 * （`%LOCALAPPDATA%\F1-RaceControl-Desktop\`；将来上 macOS 换成
 * `~/Library/Application Support/F1-RaceControl-Desktop/`）。
 *
 * 存不下、读不出来都**不许**影响启动：宁可退回 0。
 */
final class DelayStore {

    private DelayStore() {
    }

    private static File dir() {
        String local = System.getenv("LOCALAPPDATA");
        if (local != null && local.length() > 0) {
            return new File(local, "F1-RaceControl-Desktop");
        }
        String home = System.getProperty("user.home", ".");
        return new File(home, ".f1-racecontrol-desktop");
    }

    private static File file() {
        return new File(dir(), "delay.txt");
    }

    /** 读上次的延时（秒）。没有/坏了都返回 0。 */
    static int load() {
        FileInputStream in = null;
        try {
            File f = file();
            if (!f.isFile()) {
                return 0;
            }
            byte[] buf = new byte[32];
            in = new FileInputStream(f);
            int n = in.read(buf);
            if (n <= 0) {
                return 0;
            }
            int v = Integer.parseInt(new String(buf, 0, n, "UTF-8").trim());
            if (v < 0) {
                return 0;
            }
            return v > DelayGate.MAX_SECONDS ? DelayGate.MAX_SECONDS : v;
        } catch (Exception e) {
            return 0;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // 读完了，关不掉也不影响
                }
            }
        }
    }

    /** 记下当前延时。写不进去就算了。 */
    static void save(int sec) {
        FileOutputStream out = null;
        try {
            File d = dir();
            if (!d.isDirectory() && !d.mkdirs()) {
                return;
            }
            out = new FileOutputStream(file());
            out.write(String.valueOf(sec).getBytes("UTF-8"));
        } catch (IOException e) {
            // 存不下不该让程序出问题
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // 同上
                }
            }
        }
    }
}
