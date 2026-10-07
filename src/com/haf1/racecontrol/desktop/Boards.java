package com.haf1.racecontrol.desktop;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 把看板拉成**独立窗口**。
 *
 * ## 主路径：自己的原生窗口
 * 用户要的是"像微信/QQ 那样可以单独移动、调整大小的窗口"，而且任务栏图标
 * 应该是我们自己的程序。所以窗口由 {@code F1BoardWindow.exe}（WinForms +
 * WebView2，见 native/BoardWindow/）来开 —— 它是独立的进程、独立的顶层窗口，
 * 位置和尺寸都听命令行，窗口位置按看板 id 记住。
 *
 * 窗口里加载的**仍然是服务器那一份网页 UI**，所以不会出现两套渲染：
 * 主界面、本机浏览器、局域网、iPhone、原生看板窗口，都是同一份 HTML/JS。
 *
 * ## 兜底：Edge/Chrome 的 app 模式
 * 没有 .NET 运行时或没带 exe 时，退回用 Edge 开无地址栏窗口。
 * 能用，但任务栏图标是浏览器 —— 这属于降级，所以调用方会把实情打给用户看，
 * 不能默默地用降级方案冒充原生窗口。
 */
public final class Boards {

    private Boards() {
    }

    public static final String BOARD_EXE = "F1BoardWindow.exe";

    /**
     * 我们开出去的窗口，全记在这。
     *
     * 用途是**一键全关**：关掉那个 cmd 控制台窗口时，这些窗口也得跟着关，
     * 否则用户会看到一堆"连接断了"的空壳留在桌面上（他反馈过一次）。
     * 用同步 List 因为开窗口可能来自 HTTP 线程（点面板弹出）和主线程（启动参数）。
     */
    private static final List<Process> OPENED =
            java.util.Collections.synchronizedList(new ArrayList<Process>());

    /** 注册一个我们开出去的窗口。 */
    private static void track(Process p) {
        if (p != null) {
            OPENED.add(p);
        }
    }

    /**
     * 关掉所有我们开过的窗口（主界面 + 所有看板）。
     *
     * 只关还活着的，并且顺手把已经自己退出的从表里剔掉 —— 用户在界面上
     * 点过「收回」的那些早就没了，不能算进返回值里。
     *
     * @return 真的关掉了几个
     */
    public static int closeAll() {
        int n = 0;
        synchronized (OPENED) {
            List<Process> keep = new ArrayList<Process>();
            for (int i = 0; i < OPENED.size(); i++) {
                Process p = OPENED.get(i);
                if (isAlive(p)) {
                    close(p);
                    n++;
                }
                keep.add(p);
            }
            OPENED.clear();
            OPENED.addAll(keep);
        }
        return n;
    }

    /** 还有几个窗口活着。 */
    public static int liveCount() {
        int n = 0;
        synchronized (OPENED) {
            for (int i = 0; i < OPENED.size(); i++) {
                if (isAlive(OPENED.get(i))) {
                    n++;
                }
            }
        }
        return n;
    }

    // ------------------------------------------------------------------
    // 记住过位置没有
    // ------------------------------------------------------------------

    /**
     * 这块看板（这个 key）之前记住过窗口位置没有。
     *
     * 为什么要问这个：位置有两套来源 —— 用户摆好的（F1BoardWindow 存在
     * {@code %LOCALAPPDATA%\F1-RaceControl-Desktop\windows\<key>.json}）和
     * 我们给的网格槽位。命令行给的位置优先级**最高**，所以只要我们把槽位传过去，
     * 用户摆好的位置就永远用不上了 —— 而文档承诺的是"摆一次就够了"。
     * 所以：记住过就别传位置，没记住过才按槽位铺。
     *
     * ★ 路径和文件名规则必须和 native/BoardWindow/Program.cs 的
     *   WindowMemory.Safe 一致：非字母数字（保留 - 和 _）换成 _，空 key 用 default。
     */
    public static boolean hasRememberedBounds(String key) {
        try {
            String local = System.getenv("LOCALAPPDATA");
            if (local == null || local.length() == 0) {
                return false;
            }
            File f = Paths.get(local, "F1-RaceControl-Desktop", "windows",
                    safeKey(key) + ".json").toFile();
            return f.isFile() && f.length() > 0;
        } catch (Exception e) {
            return false;   // 判断不出来就当没记住，走槽位，不影响能用
        }
    }

    /** 和 WindowMemory.Safe 同一套规则。 */
    static String safeKey(String key) {
        if (key == null || key.length() == 0) {
            return "default";
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            b.append(Character.isLetterOrDigit(c) || c == '-' || c == '_' ? c : '_');
        }
        return b.toString();
    }

    // ------------------------------------------------------------------
    // 找原生窗口程序
    // ------------------------------------------------------------------

    /**
     * 定位 F1BoardWindow.exe。
     *
     * 找的地方按"发布形态优先"排：
     *   显式 --board-exe > jar 同级的 native/ > jar 同级 > 当前目录的 native/ >
     *   源码树里的构建产物（开发时直接 python tools/build.py --run 就能用）。
     */
    public static String findBoardWindow(String explicit) {
        List<Path> cands = new ArrayList<Path>();
        if (explicit != null && explicit.length() > 0) {
            cands.add(Paths.get(explicit));
        }
        Path base = selfDir();
        if (base != null) {
            cands.add(base.resolve("native").resolve(BOARD_EXE));
            cands.add(base.resolve(BOARD_EXE));
            if (base.getParent() != null) {
                cands.add(base.getParent().resolve("native").resolve(BOARD_EXE));
            }
        }
        Path cwd = Paths.get("").toAbsolutePath();
        cands.add(cwd.resolve("native").resolve(BOARD_EXE));
        cands.add(cwd.resolve("native").resolve("BoardWindow").resolve("bin")
                .resolve("Release").resolve("net9.0-windows").resolve(BOARD_EXE));
        for (Path p : cands) {
            if (Files.isRegularFile(p)) {
                return p.toString();
            }
        }
        return null;
    }

    private static Path selfDir() {
        try {
            Path self = Paths.get(Boards.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            return Files.isDirectory(self) ? self : self.getParent();
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 开窗口
    // ------------------------------------------------------------------

    /**
     * 打开结果。
     *
     * {@code process} 可能是 null（比如让浏览器自己开新标签的情况），
     * 所以调用方不能假定一定有句柄。
     */
    public static final class Opened {
        public static final String NATIVE = "native";
        public static final String BROWSER = "browser";

        public final String mode;
        public final Process process;

        Opened(String mode, Process process) {
            this.mode = mode;
            this.process = process;
        }

        /** 给用户看的说法（日志和启动信息里用）。 */
        public String describe() {
            return NATIVE.equals(mode) ? "原生窗口" : "Edge 兜底";
        }
    }

    /**
     * 开一块看板。
     *
     * @return 实际用的方式 + 进程句柄
     */
    public static Opened open(String boardExe, String browser, String key,
                              String title, String url,
                              int width, int height, int x, int y)
            throws IOException {
        if (boardExe != null) {
            List<String> cmd = new ArrayList<String>();
            cmd.add(boardExe);
            cmd.add("--url");
            cmd.add(url);
            cmd.add("--title");
            cmd.add(title);
            cmd.add("--key");
            cmd.add(key);
            if (width > 0 && height > 0) {
                cmd.add("--width");
                cmd.add(String.valueOf(width));
                cmd.add("--height");
                cmd.add(String.valueOf(height));
            }
            if (x >= 0 && y >= 0) {
                cmd.add("--x");
                cmd.add(String.valueOf(x));
                cmd.add("--y");
                cmd.add(String.valueOf(y));
            }
            return new Opened(Opened.NATIVE, spawn(cmd));
        }
        if (browser == null) {
            throw new IOException("既没有 " + BOARD_EXE + "，也没找到 Edge/Chrome");
        }
        return new Opened(Opened.BROWSER,
                spawn(edgeCommand(browser, url, key, width, height, x, y)));
    }

    /**
     * 关掉一个由我们拉起的看板窗口。
     *
     * 只用 {@code destroy()}，**没有** taskkill —— 这是实测之后的结论，不是偷懒：
     *
     * 1) Java 8 上根本拿不到子进程的 pid。{@code Process.pid()} 是 Java 9 才有的，
     *    而 Java 8 的 ProcessImpl 连 {@code toString()} 都没重写（实测就是
     *    {@code java.lang.ProcessImpl@2a139a55}），无从解析。所以
     *    "taskkill /T /PID" 在 Java 8 上永远走不到 —— 那种代码就是没跑过的死代码，
     *    留着比删掉更危险。
     * 2) 也确实不需要：WebView2 的浏览器进程挂在宿主进程的作业对象上，宿主一退
     *    它们跟着退。实测（10 个看板窗口在跑）：弹出后 11 个 F1BoardWindow +
     *    73 个 msedgewebview2，收回后回到 10 + 72，没有孤儿进程。
     *
     * 位置不会因为"强杀"而丢：F1BoardWindow 在每次移动/缩放**停下来之后**
     * 就自己写了一次记录，不等关闭（见 BoardForm.OnSaveTick）。
     */
    public static boolean close(Process p) {
        if (p == null) {
            return false;
        }
        p.destroy();
        return true;
    }

    public static boolean isAlive(Process p) {
        if (p == null) {
            return false;
        }
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException stillRunning) {
            return true;
        }
    }

    private static Process spawn(List<String> cmd) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        // 不继承我们的输出流，否则父进程收 Ctrl+C 时会把子进程一起带走。
        // ★ 不能用 ProcessBuilder.Redirect.DISCARD —— 那是 Java 9 才有的，
        //   本项目要能在 Java 8 上跑（安卓那套 JDK 就是 8）。
        File nil = nullDevice();
        pb.redirectOutput(nil);
        pb.redirectError(nil);
        // 开出来的每个窗口都登记一下：退出时要靠这张表把窗口全关掉。
        Process p = pb.start();
        track(p);
        return p;
    }

    /** 兜底方案：Edge/Chrome 的无地址栏窗口。 */
    private static List<String> edgeCommand(String browser, String url, String key,
                                            int width, int height, int x, int y) {
        List<String> cmd = new ArrayList<String>();
        cmd.add(browser);
        cmd.add("--app=" + url);
        // 每块看板一个 profile：同一个 user-data-dir 已有实例时，
        // 新窗口请求会被**转交给那个实例**，--window-size/--window-position
        // 就可能被丢掉 —— 而"每块能独立摆放"正是这个功能的意义。
        cmd.add("--user-data-dir=" + edgeProfileDir(key).toString());
        cmd.add("--no-first-run");
        cmd.add("--no-default-browser-check");
        cmd.add("--disable-features=Translate,msEdgeTranslate");
        cmd.add("--disable-session-crashed-bubble");
        if (width > 0 && height > 0) {
            cmd.add("--window-size=" + width + "," + height);
        }
        if (x >= 0 && y >= 0) {
            cmd.add("--window-position=" + x + "," + y);
        }
        return cmd;
    }

    /** Windows 的空设备是 NUL，其它平台是 /dev/null。 */
    private static File nullDevice() {
        String os = System.getProperty("os.name", "");
        return new File(os.toLowerCase().contains("win") ? "NUL" : "/dev/null");
    }

    // ------------------------------------------------------------------
    // 兜底方案用的浏览器与 profile
    // ------------------------------------------------------------------

    private static final String[][] BROWSERS = {
            {"ProgramFiles", "Microsoft", "Edge", "Application", "msedge.exe"},
            {"ProgramFiles(x86)", "Microsoft", "Edge", "Application", "msedge.exe"},
            {"LOCALAPPDATA", "Microsoft", "Edge", "Application", "msedge.exe"},
            {"ProgramFiles", "Google", "Chrome", "Application", "chrome.exe"},
            {"ProgramFiles(x86)", "Google", "Chrome", "Application", "chrome.exe"},
            {"LOCALAPPDATA", "Google", "Chrome", "Application", "chrome.exe"},
    };

    /** 找 Edge，找不到找 Chrome，都没有返回 null。 */
    public static String findBrowser() {
        for (int i = 0; i < BROWSERS.length; i++) {
            String root = System.getenv(BROWSERS[i][0]);
            if (root == null || root.length() == 0) {
                continue;
            }
            Path p = Paths.get(root, Arrays.copyOfRange(
                    BROWSERS[i], 1, BROWSERS[i].length));
            if (Files.isRegularFile(p)) {
                return p.toString();
            }
        }
        return null;
    }

    /** 兜底方案每块看板的专属 profile（不碰用户自己的浏览数据）。 */
    public static Path edgeProfileDir(String key) {
        return appDataDir().resolve("browser").resolve(safe(key));
    }

    static Path appDataDir() {
        String local = System.getenv("LOCALAPPDATA");
        return (local == null || local.length() == 0)
                ? Paths.get(System.getProperty("user.home"), ".f1-racecontrol")
                : Paths.get(local, "F1-RaceControl-Desktop");
    }

    private static String safe(String key) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; key != null && i < key.length(); i++) {
            char c = key.charAt(i);
            b.append((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' ? c : '_');
        }
        return b.length() == 0 ? "default" : b.toString();
    }

    /**
     * 一排看板的建议摆位：按 3 列网格铺开。
     *
     * 屏幕尺寸拿不到可靠值（多显示器、缩放比例都会骗人），所以只给保守建议；
     * 用户摆一次就被 F1BoardWindow 记住了，之后不用再拖。
     */
    public static int[] suggestedSlot(int index, int width, int height) {
        int cols = 3;
        return new int[]{(index % cols) * width, (index / cols) * height};
    }
}