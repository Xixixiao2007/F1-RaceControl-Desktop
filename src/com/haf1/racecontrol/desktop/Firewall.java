package com.haf1.racecontrol.desktop;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * 局域网地址 + Windows 防火墙入站规则。
 *
 * ## 为什么防火墙这件事必须专门做
 * 服务器一绑到 {@code 0.0.0.0}，Windows 防火墙会弹一个"是否允许"的框。
 * 用户点了"取消"、或者当时不在电脑前，局域网就连不上，而**本机仍然一切正常**
 * —— 于是症状表现为"手机打不开，电脑好好的"，极难自己诊断出来。
 * 所以这里主动去查、主动去加，并且**加不上就明确说出来**。
 *
 * ## 提权是躲不掉的
 * 加防火墙规则要管理员权限。Java 没法静默提权，只能走
 * {@code Start-Process -Verb RunAs} 弹一次 UAC。用户点了"否"，
 * 我们不假装成功，而是把可以直接粘到管理员 PowerShell 里的命令打出来。
 */
public final class Firewall {

    public static final String RULE_NAME = "F1-RaceControl-Desktop";

    private Firewall() {
    }

    // ------------------------------------------------------------------
    // 局域网地址
    // ------------------------------------------------------------------

    /** 本机的局域网 IPv4（跳过回环、虚拟网卡、已断开的）。 */
    public static List<String> lanAddresses() {
        List<String> out = new ArrayList<String>();
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) {
                    continue;
                }
                String name = ni.getName() == null ? "" : ni.getName().toLowerCase();
                String disp = ni.getDisplayName() == null ? "" : ni.getDisplayName();
                // 常见的虚拟网卡：Hyper-V / VMware / VirtualBox / WSL / Tailscale
                // ★ Tailscale 其实**应该**留着（不在家时靠它回家），所以不排它。
                if (name.startsWith("veth") || name.startsWith("vmnet")
                        || name.startsWith("vboxnet")
                        || disp.contains("VMware") || disp.contains("VirtualBox")
                        || disp.contains("Hyper-V") || disp.contains("Loopback")) {
                    continue;
                }
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()
                            && !a.isLinkLocalAddress()) {
                        String ip = a.getHostAddress();
                        if (!out.contains(ip)) {
                            out.add(ip);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // 拿不到就返回空，调用方会退化成本机地址
        }
        Collections.sort(out);
        return out;
    }

    public static String url(String host, int port) {
        return "http://" + host + ":" + port + "/";
    }

    // ------------------------------------------------------------------
    // 防火墙规则
    // ------------------------------------------------------------------

    /** 规则在不在。不需要管理员权限。 */
    public static boolean ruleExists() {
        int code = run(new String[]{"netsh", "advfirewall", "firewall",
                "show", "rule", "name=" + RULE_NAME}).code;
        // 规则不存在时 netsh 返回非 0（不同系统是 1，个别版本是别的）
        return code == 0;
    }

    /** 规则存在但是否真的放行了这个端口 —— 查不到就当作"不知道"。 */
    public static boolean ruleCoversPort(int port) {
        Result r = run(new String[]{"netsh", "advfirewall", "firewall",
                "show", "rule", "name=" + RULE_NAME});
        if (r.code != 0) {
            return false;
        }
        return r.out.contains(String.valueOf(port));
    }

    /**
     * 加一条入站规则，放行 TCP {@code port}（仅专用/域网络，不含公用网络 ——
     * 在咖啡馆里也没必要把看板暴露出去）。
     *
     * @return null 表示成功，否则是给用户看的原因
     */
    public static String ensureRule(int port) {
        if (ruleCoversPort(port)) {
            return null;
        }
        // 端口变了就先把旧规则删掉，免得堆一串名字相同的规则
        if (ruleExists()) {
            runElevated("advfirewall firewall delete rule name=" + RULE_NAME);
        }
        String args = "advfirewall firewall add rule name=" + RULE_NAME
                + " dir=in action=allow protocol=TCP localport=" + port
                + " profile=private,domain";
        Result r = runElevated(args);
        if (r.code == 0 && (ruleExists() || ruleCoversPort(port))) {
            return null;
        }
        if (r.code == 0) {
            // 命令"成功"了但规则查不到 —— 别报成功
            return "命令返回成功，但规则查不到（可能被组策略拦了）";
        }
        String detail = r.out.trim();
        if (detail.length() == 0) {
            detail = "退出码 " + r.code;
        }
        return "没加成（" + firstLine(detail) + "）";
    }

    public static String manualCommand(int port) {
        return "netsh advfirewall firewall add rule name=" + RULE_NAME
                + " dir=in action=allow protocol=TCP localport=" + port
                + " profile=private,domain";
    }

    public static String removeCommand() {
        return "netsh advfirewall firewall delete rule name=" + RULE_NAME;
    }

    // ------------------------------------------------------------------

    /**
     * 弹一次 UAC 跑 netsh。
     *
     * ★ Start-Process -Verb RunAs 在用户点"否"时会让 PowerShell 抛异常，
     *   进程退出码非 0 —— 这正是我们要的信号，不能吞掉。
     */
    private static Result runElevated(String netshArgs) {
        String script = "Start-Process -Verb RunAs -Wait -WindowStyle Hidden "
                + "-FilePath netsh.exe -ArgumentList '" + netshArgs + "'";
        return run(new String[]{"powershell.exe", "-NoProfile",
                "-NonInteractive", "-Command", script});
    }

    private static final class Result {
        final int code;
        final String out;

        Result(int code, String out) {
            this.code = code;
            this.out = out;
        }
    }

    private static Result run(String[] cmd) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            p = pb.start();
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[4096];
            int n;
            while ((n = p.getInputStream().read(buf)) > 0) {
                sb.append(new String(buf, 0, n, "GBK"));
            }
            int code = p.waitFor();
            return new Result(code, sb.toString());
        } catch (IOException e) {
            return new Result(-1, e.getClass().getSimpleName() + ": "
                    + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, "被中断");
        } finally {
            if (p != null) {
                p.destroy();
            }
        }
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        String one = i < 0 ? s : s.substring(0, i);
        return one.trim();
    }
}