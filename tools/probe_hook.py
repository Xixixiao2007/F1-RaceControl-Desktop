#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""探针：Windows 上关控制台窗口 / 按 Ctrl+C，JVM 的 shutdown hook 到底跑不跑？

这个脚本存在的唯一原因：**它推翻了一个想当然的设计。**

原本的计划是"在服务器的退出钩子里把看板窗口一起关掉"。写下这段代码之后
用这个探针一测，发现两条路完全不一样：

    关掉控制台窗口（用户点 X）   → shutdown hook 不执行
    按 Ctrl+C                    → shutdown hook 执行

所以"关掉 cmd 窗口之后小窗还留着"这个问题**不能**靠退出钩子解决，
F1BoardWindow 才会去轮询 `/api/health` 自己发现服务器没了（见 test_watchdog.py）。

留在这里是因为：没有它，将来有人看到"窗口每 3 秒 ping 一次服务器"这种
看起来多余的代码，很可能会"简化"回退出钩子 —— 然后这个 bug 原样回来。

用法：python tools\\probe_hook.py
需要 JDK 8（javac + java），路径自动找，找不到就用 PATH 里的。
"""
import ctypes
import ctypes.wintypes as wt
import os
import subprocess
import sys
import time

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TMP = os.path.join(REPO, "build", "probe_hook")
os.makedirs(TMP, exist_ok=True)

CANDIDATES = [
    r"C:\Program Files\Android\jdk\jdk-8.0.302.8-hotspot\jdk8u302-b08\bin",
    os.path.join(os.environ.get("JAVA_HOME", ""), "bin"),
]
BIN = None
for c in CANDIDATES:
    if c and os.path.isfile(os.path.join(c, "javac.exe")):
        BIN = c
        break
if BIN is None:
    BIN = ""
JAVAC = os.path.join(BIN, "javac.exe") if BIN else "javac"
JAVA = os.path.join(BIN, "java.exe") if BIN else "java"

u = ctypes.windll.user32
k = ctypes.windll.kernel32

SRC = os.path.join(TMP, "HookProbe.java")
CLS = os.path.join(TMP, "classes")
os.makedirs(CLS, exist_ok=True)

# 钩子里只做一件事：往文件里追加一行。能读到这一行 = 钩子跑了。
with open(SRC, "w", encoding="utf-8") as f:
    f.write(
        "import java.io.*;\n"
        "public class HookProbe {\n"
        "  public static void main(String[] a) throws Exception {\n"
        "    final String out = a[0];\n"
        "    Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {\n"
        "      public void run() {\n"
        "        try { FileWriter w = new FileWriter(out, true);\n"
        "              w.write(\"hook ran\\n\"); w.close(); } catch (Exception e) {}\n"
        "      }\n"
        "    }));\n"
        "    System.out.println(\"ready\");\n"
        "    Thread.sleep(600000L);\n"
        "  }\n"
        "}\n")


def find_window(title):
    hit = []

    @ctypes.WINFUNCTYPE(wt.BOOL, wt.HWND, wt.LPARAM)
    def cb(h, l):
        if not u.IsWindowVisible(h):
            return True
        n = u.GetWindowTextLengthW(h)
        b = ctypes.create_unicode_buffer(n + 2)
        u.GetWindowTextW(h, b, n + 1)
        if b.value == title:
            hit.append(h)
        return True

    u.EnumWindows(cb, 0)
    return hit[0] if hit else None


def run_in_console(title, out):
    if os.path.exists(out):
        os.remove(out)
    cmd = os.path.join(TMP, title + ".cmd")
    with open(cmd, "w", encoding="gbk") as f:
        f.write("@echo off\r\ntitle %s\r\n\"%s\" -cp \"%s\" HookProbe \"%s\"\r\n"
                % (title, JAVA, CLS, out))
    p = subprocess.Popen(["cmd.exe", "/c", cmd],
                         creationflags=subprocess.CREATE_NEW_CONSOLE)
    for _ in range(60):
        time.sleep(0.5)
        if find_window(title):
            break
    time.sleep(3)
    return p, find_window(title)


def hook_ran(out):
    return os.path.exists(out) and "hook ran" in open(out).read()


def main():
    r = subprocess.run([JAVAC, "-d", CLS, SRC], capture_output=True, text=True)
    if r.returncode != 0:
        print("★ javac 失败（找不到 JDK 8？）：%s" % r.stderr.strip()[:300])
        return 2
    print("探测程序编译好了（%s）" % JAVAC)
    print()

    print("=== 一、关掉控制台窗口（用户点右上角的 X）===")
    p1, h1 = run_in_console("PROBE1", os.path.join(TMP, "close.txt"))
    print("  控制台窗口 %s，cmd pid=%d" % (h1, p1.pid))
    if h1:
        u.PostMessageW(h1, 0x0010, 0, 0)   # WM_CLOSE
    time.sleep(8)
    a = hook_ran(os.path.join(TMP, "close.txt"))
    print("  => shutdown hook %s" % ("跑了 ✓" if a else "没跑 ✗"))

    print()
    print("=== 二、按 Ctrl+C ===")
    p2, h2 = run_in_console("PROBE2", os.path.join(TMP, "ctrlc.txt"))
    pid = None
    for line in subprocess.run(
            ["powershell", "-NoProfile", "-Command",
             "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | "
             "Where-Object { $_.CommandLine -like '*HookProbe*' } | "
             "ForEach-Object { $_.ProcessId }"],
            capture_output=True, text=True).stdout.split():
        pid = int(line)
    print("  控制台窗口 %s，java pid=%s" % (h2, pid))
    if pid:
        k.FreeConsole()
        if k.AttachConsole(pid):
            k.SetConsoleCtrlHandler(None, True)    # 别把自己也打死
            print("  GenerateConsoleCtrlEvent(CTRL_C) -> %s"
                  % k.GenerateConsoleCtrlEvent(0, 0))
            time.sleep(6)
            k.FreeConsole()
    b = hook_ran(os.path.join(TMP, "ctrlc.txt"))
    print("  => shutdown hook %s" % ("跑了 ✓" if b else "没跑 ✗"))

    for p in (p1, p2):
        subprocess.run(["taskkill", "/F", "/T", "/PID", str(p.pid)],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    print()
    if a is False and b is True:
        print("结论：关控制台窗口**不**执行退出钩子，Ctrl+C 执行。")
        print("      所以看板窗口必须自己轮询 /api/health（见 test_watchdog.py）。")
    else:
        print("★ 结果和记录的不一样（关窗=%s，Ctrl+C=%s）——"
              "如果是换了 JDK 版本，请更新文档里的结论。" % (a, b))
    return 0


if __name__ == "__main__":
    sys.exit(main())