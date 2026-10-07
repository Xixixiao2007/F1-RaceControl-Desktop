package com.haf1.racecontrol.desktop;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.haf1.racecontrol.F1Client;
import com.haf1.racecontrol.FeedSource;
import com.haf1.racecontrol.ReplayClient;

/**
 * 桌面版入口。
 *
 * 一个进程干三件事：连 F1（用安卓那份 F1Client 源码本体）→ 起 Web 服务器 →
 * 把看板拉成独立窗口。局域网和 iPhone 看到的是同一份界面。
 */
public final class DesktopMain {

    public static final String VERSION = "0.1.5";
    public static final int DEFAULT_PORT = 8720;

    private DesktopMain() {
    }

    public static void main(String[] args) throws Exception {
        Options o;
        try {
            o = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.out.println("参数错误：" + e.getMessage());
            System.out.println();
            usage();
            System.exit(2);
            return;
        }
        if (o.help) {
            usage();
            return;
        }
        if (o.listBoards) {
            for (int i = 0; i < WebServer.BOARDS.length; i++) {
                System.out.println("  " + pad(WebServer.BOARDS[i][0], 10)
                        + WebServer.BOARDS[i][1]);
            }
            return;
        }

        final AppCore core = new AppCore(o.replay != null, o.replayLabel, VERSION);
        FeedSource src;
        if (o.replay != null) {
            final File f = new File(o.replay);
            if (!f.isFile()) {
                System.out.println("回放文件不存在：" + f.getAbsolutePath());
                System.exit(2);
                return;
            }
            src = new ReplayClient(new ReplayClient.Opener() {
                @Override
                public InputStream open() throws IOException {
                    return new FileInputStream(f);
                }
            }, core, o.speed, 0L);
        } else {
            src = new F1Client(core);
        }
        core.attach(src);

        Path webDir = WebServer.findWebDir(o.web);
        // 窗口程序必须在这里就定下来：Web 服务器要用它来实现
        // 「在网页上点某块面板 → 弹出独立小窗」。
        String boardExe = Boards.findBoardWindow(o.boardExe);
        String browser = boardExe == null ? Boards.findBrowser() : null;
        WebServer srv = new WebServer(core, webDir, o.port);
        srv.setWindowContext(boardExe, browser, o.boardWidth, o.boardHeight);
        srv.start();
        int port = srv.port();

        List<String> lan = Firewall.lanAddresses();

        System.out.println("F1-Race Control 桌面版 " + VERSION);
        System.out.println("  界面目录 : " + (webDir == null ? "（用 jar 内置的）" : webDir));
        System.out.println("  数据源   : " + (o.replay != null
                ? "回放 " + o.replayLabel + "（" + o.speed + " 倍速）"
                : "官方公开流（实时）"));
        System.out.println("  本机     : " + Firewall.url("127.0.0.1", port));
        if (lan.isEmpty()) {
            System.out.println("  局域网   : （没找到局域网地址）");
        } else {
            for (int i = 0; i < lan.size(); i++) {
                System.out.println("  局域网   : " + Firewall.url(lan.get(i), port)
                        + (i == 0 ? "   <- 手机 / iPad 用这个" : ""));
            }
        }

        if (o.firewall) {
            System.out.println("  防火墙   : 正在检查并添加规则"
                    + (Firewall.ruleCoversPort(port) ? "（已有）" : "（会弹一次 UAC）") + "……");
            String err = Firewall.ensureRule(port);
            if (err == null) {
                System.out.println("  防火墙   : 已放行 TCP " + port + "（专用/域网络）");
            } else {
                System.out.println("  防火墙   : " + err);
                System.out.println("             局域网会连不上（本机不受影响）。"
                        + "想手动加，用管理员 PowerShell 跑：");
                System.out.println("             " + Firewall.manualCommand(port));
            }
        } else {
            // ★ 不加 --firewall 也要把话说完整。
            //   只说"加 --firewall"是不够的：受管控的机器加不了、不想点 UAC 的人
            //   也需要一条能直接粘贴的命令，不然用户只能自己去翻文档。
            boolean covered = Firewall.ruleCoversPort(port);
            System.out.println("  防火墙   : " + (covered
                    ? "已有规则放行 TCP " + port + "，局域网应该能连"
                    : "未放行 TCP " + port + "（本机不受影响，但手机连不上）"));
            if (!covered) {
                System.out.println("             自动加：加 --firewall 参数（会弹一次 UAC）");
                System.out.println("             手动加：管理员 PowerShell 里跑下面这行");
                System.out.println("             " + Firewall.manualCommand(port));
            }
        }

        // 只诊断、不改动：不弹 UAC 也能看清到底卡在哪
        if (o.firewallCheck) {
            System.out.println("  防火墙检查: 规则名 " + Firewall.RULE_NAME
                    + (Firewall.ruleExists() ? " 存在" : " 不存在"));
            System.out.println("             端口 " + port + " "
                    + (Firewall.ruleCoversPort(port) ? "已被规则覆盖" : "未被覆盖"));
            System.out.println("             本机监听 "
                    + (Firewall.portListening(port) ? "正常" : "★ 没在监听"));
        }

        // 数据线程。★ 非守护线程：主线程靠它活着，进程不会提前退出。
        Thread feed = new Thread(new Runnable() {
            @Override
            public void run() {
                core.source().runForever();
            }
        }, "f1-feed");
        feed.start();

        boolean wantWindows = o.openMain || !o.boards.isEmpty();
        if (wantWindows) {
            if (boardExe != null) {
                System.out.println("  窗口程序 : 原生 F1BoardWindow.exe");
                System.out.println("             " + boardExe);
            } else if (browser != null) {
                // ★ 降级就说降级。用浏览器窗口冒充原生窗口是不可接受的：
                //   用户明确要的是"任务栏图标是我们自己程序"的窗口。
                System.out.println("  窗口程序 : ★ 降级用 Edge/Chrome 无地址栏窗口");
                System.out.println("             " + browser);
                System.out.println("             没找到 " + Boards.BOARD_EXE
                        + "（任务栏图标会是浏览器）。");
                System.out.println("             想要原生窗口：装 .NET 运行时后跑"
                        + " tools/build_native.py，或用 --board-exe 指定路径。");
            } else {
                System.out.println("  窗口程序 : ★ 没有，窗口开不了。"
                        + "用上面那个地址在浏览器里打开一样能用。");
            }
        }

        List<String[]> opened = new ArrayList<String[]>();
        if (o.openMain || !o.boards.isEmpty()) {
            if (boardExe != null || browser != null) {
                if (o.openMain) {
                    String url = Firewall.url("127.0.0.1", port);
                    // 位置交给窗口自己定：先看有没有记住过（用户摆好的优先），
                    // 没有才用默认。★ 这里传 -1 是关键 —— 只要传了坐标，
                    // 窗口那边就会当成"用户显式指定"，记住的位置永远轮不上。
                    String how = openWindow(boardExe, browser, "main", "F1 Race Control",
                            url, o.mainWidth, o.mainHeight, -1, -1);
                    opened.add(new String[]{"主界面" + how, url});
                }
                int idx = 0;
                for (int i = 0; i < o.boards.size(); i++) {
                    String id = o.boards.get(i);
                    if (!isBoard(id)) {
                        System.out.println("  ★ 没有这个看板：" + id);
                        continue;
                    }
                    // 摆过位置的就回到原位；没摆过才按网格铺开，
                    // 否则 --all-boards 第一次会 9 个窗口叠在一起。
                    int[] pos = Boards.hasRememberedBounds(id)
                            ? new int[]{-1, -1}
                            : Boards.suggestedSlot(idx, o.boardWidth, o.boardHeight);
                    String url = Firewall.url("127.0.0.1", port) + "board/" + id;
                    String how = openWindow(boardExe, browser, id, boardTitle(id),
                            url, o.boardWidth, o.boardHeight, pos[0], pos[1]);
                    opened.add(new String[]{boardTitle(id) + how, url});
                    idx++;
                }
            }
        }
        for (int i = 0; i < opened.size(); i++) {
            System.out.println("  已打开   : " + opened.get(i)[0] + "  "
                    + opened.get(i)[1]);
        }
        System.out.println();
        System.out.println("Ctrl+C 退出。");

        final FeedSource stopSrc = src;
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                // ★ 关掉控制台窗口时，我们自己开出去的窗口也要跟着关。
                //   否则桌面上会留一堆"连接断了"的空壳，用户还得一个个去点 X
                //   （他反馈过这个）。这件事必须在钩子里做：JVM 退出后没人再管它们。
                //   注意：任务管理器"结束进程"这类强杀不会走钩子，那种情况下
                //   窗口会留着 —— 但位置记着，重开就回到原位。
                try {
                    int n = Boards.closeAll();
                    if (n > 0) {
                        System.out.println("已关闭 " + n + " 个窗口。");
                    }
                } catch (Exception ignored) {
                    // 退出路径，尽量别抛
                }
                try {
                    stopSrc.stop();
                } catch (Exception ignored) {
                    // 退出路径，尽量别抛
                }
            }
        }, "shutdown"));

        // 主线程挂着等（数据线程是非守护的，这里 join 它即可）
        feed.join();
    }

    /** 开一个窗口，返回实际用的方式（附在标题后面，让用户一眼看出是不是降级）。 */
    private static String openWindow(String boardExe, String browser, String key,
                                     String title, String url,
                                     int w, int h, int x, int y) {
        try {
            String how = Boards.open(boardExe, browser, key, title, url, w, h, x, y)
                    .describe();
            return boardExe != null ? "" : "（" + how + "）";
        } catch (IOException e) {
            System.out.println("  ★ 开窗口失败：" + e.getMessage());
            return "（开失败）";
        }
    }

    private static boolean isBoard(String id) {
        for (int i = 0; i < WebServer.BOARDS.length; i++) {
            if (WebServer.BOARDS[i][0].equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static String boardTitle(String id) {
        for (int i = 0; i < WebServer.BOARDS.length; i++) {
            if (WebServer.BOARDS[i][0].equals(id)) {
                return WebServer.BOARDS[i][1];
            }
        }
        return id;
    }

    private static String pad(String s, int n) {
        StringBuilder b = new StringBuilder(s);
        while (b.length() < n) {
            b.append(' ');
        }
        return b.toString();
    }

    private static void usage() {
        System.out.println("F1-Race Control 桌面版 " + VERSION);
        System.out.println();
        System.out.println("用法: java -jar F1-RaceControl-Desktop.jar [选项]");
        System.out.println();
        System.out.println("  --port N           监听端口（默认 " + DEFAULT_PORT + "，0 = 随便挑一个）");
        System.out.println("  --web DIR          界面目录（默认自动找 web/）");
        System.out.println("  --board-exe PATH   F1BoardWindow.exe 的路径（默认自动找）");
        System.out.println("  --open             启动时打开主界面窗口（**这是默认**，想关掉用 --no-window）");
        System.out.println("  --board a,b,c      启动时就拉出指定看板（平时不用：界面上点「弹出」即可）");
        System.out.println("  --all-boards       启动时把全部 9 个看板都拉出来");
        System.out.println("  --list-boards      列出看板 id 就退出");
        System.out.println("  --firewall         自动加 Windows 防火墙入站规则（会弹一次 UAC）");
        System.out.println("  --firewall-check   只检查防火墙状态和端口监听，不做任何改动、不提权");
        System.out.println("  --replay FILE      放 .rclog 回放文件（不连网，用来演练界面）");
        System.out.println("  --speed N          回放倍速（默认 60）");
        System.out.println("  --main-size WxH    主窗口尺寸（默认 1600x900）");
        System.out.println("  --board-size WxH   看板窗口尺寸（默认 620x420）");
        System.out.println("  --no-window        只起服务器，不开任何窗口");
        System.out.println("  -h, --help         看这个");
        System.out.println();
        System.out.println("默认只开主界面；看板小窗在界面上点标题栏的「弹出」拉出来，");
        System.out.println("再点「收回」或点顶部的「收回全部」关掉。关掉这个控制台窗口时");
        System.out.println("（或者按 Ctrl+C），我们自己开的窗口也会一起关。");
    }

    // ------------------------------------------------------------------

    static final class Options {
        int port = DEFAULT_PORT;
        String web;
        String boardExe;
        /**
         * 启动时开不开主界面窗口。
         *
         * ★ 默认 **true**：直接运行就等于"只开主屏"。看板窗口是用户自己
         *   在界面上点「弹出」拉出来的 —— 一上来铺 9 个窗口会糊满整个屏幕，
         *   而且大部分时候用户只想看总览。
         */
        boolean openMain = true;
        boolean noWindow;
        final List<String> boards = new ArrayList<String>();
        boolean firewall;
        boolean firewallCheck;
        String replay;
        String replayLabel = "";
        int speed = 60;
        boolean help;
        boolean listBoards;
        int mainWidth = 1600;
        int mainHeight = 900;
        int boardWidth = 620;
        int boardHeight = 420;

        static Options parse(String[] a) {
            Options o = new Options();
            for (int i = 0; i < a.length; i++) {
                String s = a[i];
                if ("-h".equals(s) || "--help".equals(s)) {
                    o.help = true;
                } else if ("--port".equals(s)) {
                    o.port = Integer.parseInt(next(a, ++i, s));
                    if (o.port < 0 || o.port > 65535) {
                        throw new IllegalArgumentException("端口越界: " + o.port);
                    }
                } else if ("--web".equals(s)) {
                    o.web = next(a, ++i, s);
                } else if ("--board-exe".equals(s)) {
                    o.boardExe = next(a, ++i, s);
                } else if ("--open".equals(s)) {
                    o.openMain = true;
                } else if ("--no-window".equals(s)) {
                    o.noWindow = true;
                } else if ("--board".equals(s)) {
                    addBoards(o.boards, next(a, ++i, s));
                } else if ("--all-boards".equals(s)) {
                    for (int k = 0; k < WebServer.BOARDS.length; k++) {
                        o.boards.add(WebServer.BOARDS[k][0]);
                    }
                } else if ("--list-boards".equals(s)) {
                    o.listBoards = true;
                } else if ("--firewall".equals(s)) {
                    o.firewall = true;
                    o.firewallCheck = true;   // 加规则时顺便把检查也报出来
                } else if ("--firewall-check".equals(s)) {
                    o.firewallCheck = true;
                } else if ("--replay".equals(s)) {
                    o.replay = next(a, ++i, s);
                } else if ("--speed".equals(s)) {
                    o.speed = Integer.parseInt(next(a, ++i, s));
                    if (o.speed <= 0) {
                        throw new IllegalArgumentException("倍速要大于 0");
                    }
                } else if ("--main-size".equals(s)) {
                    int[] wh = size(next(a, ++i, s));
                    o.mainWidth = wh[0];
                    o.mainHeight = wh[1];
                } else if ("--board-size".equals(s)) {
                    int[] wh = size(next(a, ++i, s));
                    o.boardWidth = wh[0];
                    o.boardHeight = wh[1];
                } else if (s.startsWith("--port=")) {
                    o.port = Integer.parseInt(s.substring(7));
                } else if (s.startsWith("--board=")) {
                    addBoards(o.boards, s.substring(8));
                } else if (s.startsWith("--replay=")) {
                    o.replay = s.substring(9);
                } else {
                    throw new IllegalArgumentException("不认识: " + s);
                }
            }
            if (o.noWindow) {
                o.openMain = false;
                o.boards.clear();
            }
            if (o.replay != null) {
                o.replayLabel = new File(o.replay).getName();
            }
            return o;
        }

        private static void addBoards(List<String> out, String csv) {
            String[] parts = csv.split(",");
            for (int i = 0; i < parts.length; i++) {
                String p = parts[i].trim();
                if (p.length() > 0 && !out.contains(p)) {
                    out.add(p);
                }
            }
        }

        private static String next(String[] a, int i, String flag) {
            if (i >= a.length) {
                throw new IllegalArgumentException(flag + " 后面要跟一个值");
            }
            return a[i];
        }

        private static int[] size(String s) {
            int x = s.indexOf('x');
            if (x < 0) {
                x = s.indexOf('X');
            }
            if (x < 0) {
                throw new IllegalArgumentException("尺寸要写成 宽x高: " + s);
            }
            int w = Integer.parseInt(s.substring(0, x).trim());
            int h = Integer.parseInt(s.substring(x + 1).trim());
            if (w <= 0 || h <= 0) {
                throw new IllegalArgumentException("尺寸要为正: " + s);
            }
            return new int[]{w, h};
        }
    }
}