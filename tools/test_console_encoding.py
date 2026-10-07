#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""回归测试：**真控制台窗口**里 Java 打的中文不能是乱码/问号。

为什么必须这么测（这就是当初漏掉它的原因）：
  我们的其它测试都是把子进程输出**重定向到文件/管道**再读。重定向时 Java 用的是
  file.encoding，输出是对的 —— 这个 bug 在重定向下**完全不出现**，只有真的坐在
  一个控制台窗口里才会现形。用户截图里"编码不对，都是问号"就是这么来的。

根因：启动脚本 `chcp 65001` 之后，Java 8 把 sun.stdout.encoding 报成 cp65001，
可它并不真认这个代码页，PrintStream 用的是平台默认编码（实测 GBK），于是写出
GBK 字节、控制台按 UTF-8 解 → 中文全成替换字符。对照实验见
dsh\\tools\\probe_console_encoding.py。

本测试跑两种启动方式，都必须正常：
  1. 直接 `java -jar`（靠 DesktopMain.fixConsoleEncoding 兜底）
  2. 启动脚本那样带 -Dsun.stdout.encoding=UTF-8

用法：python tools\\test_console_encoding.py [--app 目录]
"""
import argparse
import ctypes
import ctypes.wintypes as wt
import os
import subprocess
import sys
import time

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.environ.get("JAVA_HOME") or ""
JAVA = (os.path.join(JAVA, "bin", "java.exe") if JAVA else
        (r"C:\Program Files\Android\jdk\jdk-8.0.302.8-hotspot\jdk8u302-b08\bin\java.exe"))
if not os.path.isfile(JAVA):
    JAVA = "java"
ok = True
k = ctypes.WinDLL("kernel32", use_last_error=True)


class COORD(ctypes.Structure):
    _fields_ = [("X", ctypes.c_short), ("Y", ctypes.c_short)]


class SMALL_RECT(ctypes.Structure):
    _fields_ = [("Left", ctypes.c_short), ("Top", ctypes.c_short),
                ("Right", ctypes.c_short), ("Bottom", ctypes.c_short)]


class CSBI(ctypes.Structure):
    _fields_ = [("dwSize", COORD), ("dwCursorPosition", COORD),
                ("wAttributes", wt.WORD), ("srWindow", SMALL_RECT),
                ("dwMaximumWindowSize", COORD)]


def check(label, cond, extra=""):
    global ok
    if not cond:
        ok = False
    print("  [%s] %-46s %s" % ("OK" if cond else "!!", label, extra))


def read_console(pid):
    """读那个进程所在控制台的文字（控制台解码后的结果 = 用户看到的字）。"""
    k.FreeConsole()
    if not k.AttachConsole(pid):
        return None
    try:
        h = k.CreateFileW("CONOUT$", 0x80000000 | 0x40000000, 3, None, 3, 0, None)
        info = CSBI()
        if not k.GetConsoleScreenBufferInfo(h, ctypes.byref(info)):
            return None
        n = info.dwSize.X * min(info.dwSize.Y, info.dwCursorPosition.Y + 2)
        buf = ctypes.create_unicode_buffer(n + 1)
        got = ctypes.c_ulong()
        k.ReadConsoleOutputCharacterW(h, buf, n, COORD(0, 0), ctypes.byref(got))
        txt = buf[:got.value]
        return [txt[i:i + info.dwSize.X].rstrip()
                for i in range(0, len(txt), info.dwSize.X) if txt[i:i + info.dwSize.X].strip()]
    finally:
        k.FreeConsole()


def find_jar(app):
    for c in (os.path.join(app, "F1-RaceControl-Desktop.jar"),
              os.path.join(app, "build", "F1-RaceControl-Desktop.jar")):
        if os.path.isfile(c):
            return c
    return None


def run(tag, app, jar, flags):
    cmd = os.path.join(REPO, "build", "console_enc", tag + ".cmd")
    os.makedirs(os.path.dirname(cmd), exist_ok=True)
    # .cmd 用 UTF-8 写、不带 BOM（和 make_dist.py 生成的一致）
    with open(cmd, "w", encoding="utf-8", newline="\r\n") as f:
        f.write("@echo off\n")
        f.write("title %s\n" % tag)
        f.write("chcp 65001 >nul\n")
        f.write('cd /d "%s"\n' % app)
        f.write('"%s" %s -jar "%s" --list-boards\n' % (JAVA, flags, jar))
        f.write("pause\n")
    p = subprocess.Popen(["cmd.exe", "/c", cmd],
                         creationflags=subprocess.CREATE_NEW_CONSOLE)
    time.sleep(4)
    lines = read_console(p.pid) or []
    subprocess.run(["taskkill", "/F", "/T", "/PID", str(p.pid)],
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(0.5)
    return lines


def run_launcher(tag, app, port):
    """直接跑打包出来的「启动.cmd」——用户就是这么双击的。

    加 --no-window：只要那个头部输出，不开窗口、不碰用户的桌面。
    （就算真开了窗口，收尾按端口 kill 掉，不会留东西。）
    """
    launcher = None
    for n in os.listdir(app):
        if n.endswith(".cmd") and "启动" in n and "局域网" not in n:
            launcher = os.path.join(app, n)
    if not launcher:
        return None
    cmd = os.path.join(REPO, "build", "console_enc", tag + ".cmd")
    with open(cmd, "w", encoding="utf-8", newline="\r\n") as f:
        # ★ 第一行就得切到 65001：下面的 `call` 里含中文文件名，cmd 是按**当时的
        #   代码页**解这一行的字节的。不切的话它按 GBK 解 UTF-8 的字节 →
        #   找不到文件 → 整个脚本立刻退出 → 控制台跟着关掉
        #   （表现出来就是"读到空控制台"、AttachConsole 错误码 5）。
        #   踩过一次，排查了半天。
        f.write("@echo off\n")
        f.write("chcp 65001 >nul\n")
        f.write("title %s\n" % tag)
        f.write('call "%s" --no-window --port %s\n' % (launcher, port))
    p = subprocess.Popen(["cmd.exe", "/c", cmd],
                         creationflags=subprocess.CREATE_NEW_CONSOLE)
    time.sleep(14)
    alive = p.poll() is None
    if not alive:
        print("   ★ 那个 cmd 已经退出了 —— 多半是 call 没找到启动脚本")
    lines = read_console(p.pid)
    if lines is None:
        print("   ★ 读不到控制台（进程可能已经没了）")
    lines = lines or []
    subprocess.run(["taskkill", "/F", "/T", "/PID", str(p.pid)],
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(1)
    # 兜底：把这个端口上的窗口/进程清掉（不该有，但别留）
    ps = ("Get-CimInstance Win32_Process -Filter \"Name='F1BoardWindow.exe'\" | "
          "Where-Object { $_.CommandLine -like '*:%s*' } | "
          "ForEach-Object { Stop-Process -Id $_.ProcessId -Force }" % port)
    subprocess.run(["powershell", "-NoProfile", "-Command", ps],
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return lines


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--app", default=REPO)
    a = ap.parse_args()
    app = os.path.abspath(a.app)
    jar = find_jar(app)
    if not jar:
        print("★ %s 里找不到 jar（先 python tools\\build.py）" % app)
        return 1
    if not sys.platform.startswith("win"):
        print("  [--] 非 Windows，跳过")
        return 0
    print("被测 jar: %s" % jar)

    print("== 1. 直接 java -jar（Java 侧兜底）==")
    one = run("F1ENCPLAIN", app, jar, "")
    for l in one:
        print("   " + l)
    t1 = "\n".join(one)

    print("== 2. 启动脚本那样带 -Dsun.stdout.encoding=UTF-8 ==")
    two = run("F1ENCFLAG", app, jar,
              "-Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8")
    for l in two:
        print("   " + l)
    t2 = "\n".join(two)

    print("== 3. 直接跑打包出来的「启动.cmd」（用户双击的那条路）==")
    port = 0
    import socket
    for p0 in range(9400, 9460):
        s = socket.socket()
        try:
            s.bind(("127.0.0.1", p0))
            port = p0
            break
        except OSError:
            pass
        finally:
            s.close()
    three = run_launcher("F1ENCLAUNCH", app, port)
    if three is None:
        print("   [--] 这个目录里没有「启动.cmd」，跳过（开发目录就是这样）")
    else:
        for l in three:
            print("   " + l)

    bad = "\ufffd"
    check("① 看得见中文看板名（没有乱码）",
          "赛道图" in t1 and bad not in t1 and "??" not in t1,
          "出现 %s" % ("赛道图" if "赛道图" in t1 else "（没有）"))
    check("② 带 -Dsun.stdout.encoding=UTF-8 也正常",
          "赛道图" in t2 and bad not in t2 and "??" not in t2)
    check("③ 9 个看板都打出来了",
          sum(1 for l in one if l.strip()) >= 9,
          sum(1 for l in one if l.strip()))
    if three is not None:
        t3 = "\n".join(three)
        check("④ 启动脚本打出的头部是中文（用户看到的那几行）",
              "桌面版" in t3 and bad not in t3 and "??" not in t3,
              "头部那行：%s" % ([l for l in three if "Race Control" in l][:1] or "（没找到）"))
    print()
    print("全部通过 ✓" if ok else "★ 有失败项（控制台里的中文还是会花）")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())