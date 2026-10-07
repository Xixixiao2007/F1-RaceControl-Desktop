#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""验证「服务器没了，看板窗口会自己关」—— 走用户真正会走的那条路。

为什么要专门测这条：**关掉 cmd 控制台窗口**时 JVM 的 shutdown hook 不执行
（见 tools/probe_hook.py 的实测），所以"关窗"这件事只能由窗口自己发现。
这个脚本就照着用户的动作走一遍：

  1. 用**真控制台窗口**（cmd.exe + CREATE_NEW_CONSOLE）启动服务器
  2. 弹出两块看板，确认窗口在
  3. 先证明不误杀：等 22 秒，服务器活着时窗口必须还在
  4. 给那个控制台窗口发 WM_CLOSE（= 鼠标点右上角的 X），等同用户操作
  5. 30 秒内所有窗口必须自己关掉，0 残留

用法（先编译：python tools\\build.py --native）：

    python tools\\test_watchdog.py [端口]
    python tools\\test_watchdog.py 8790 --app <解压出来的发布包目录>

带 `--app` 时用的是**那个目录里的** jar 和 F1BoardWindow.exe —— 验收发布包
就得这样验，否则验的是开发目录，包里的东西坏没坏根本不知道。

脚本只关自己这个端口的窗口和进程，不动你自己开的那套。
"""
import argparse
import ctypes
import ctypes.wintypes as wt
import json
import os
import subprocess
import sys
import time
import urllib.request

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.environ.get("JAVA_HOME") or ""
JAVA = (os.path.join(JAVA, "bin", "java.exe") if JAVA else
        (r"C:\Program Files\Android\jdk\jdk-8.0.302.8-hotspot\jdk8u302-b08\bin\java.exe"))
if not os.path.isfile(JAVA):
    JAVA = "java"          # 交给 PATH

u = ctypes.windll.user32
WM_CLOSE = 0x0010
ok = True
# 和 native/BoardWindow/Program.cs 的 ShellMarker、Java 的 WebServer.SHELL_MARKER
# 三处必须一致。
SHELL_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) F1RaceControlShell/1"


def find_jar(app):
    """仓库里 jar 在 build/，发布包里就在根目录。"""
    for c in (os.path.join(app, "F1-RaceControl-Desktop.jar"),
              os.path.join(app, "build", "F1-RaceControl-Desktop.jar")):
        if os.path.isfile(c):
            return c
    return None


def check(label, cond, extra=""):
    global ok
    if not cond:
        ok = False
    print("  [%s] %-42s %s" % ("OK" if cond else "!!", label, extra))


def http(path, method="GET", timeout=8):
    """带上原生窗口的 UA 标记。

    ★ 从 0.1.5 起服务器分两种"弹出"：跑在我们自己窗口里的页面 → 开原生小窗；
      浏览器里的页面（哪怕在本机）→ 开网页标签页。这个测试要的是原生小窗，
      所以必须带标记 —— 不带的话服务器会（正确地）返回 mode=tab，一个窗口
      都不会有，测试就会假装失败。这个 UA 和 native/BoardWindow 里的那个常量
      必须一致（Java 侧见 WebServer.SHELL_MARKER）。
    """
    o = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    r = urllib.request.Request("http://127.0.0.1:%s%s" % (PORT, path),
                               headers={"User-Agent": SHELL_UA}, method=method)
    with o.open(r, timeout=timeout) as f:
        return json.loads(f.read().decode())


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


def my_windows():
    """只认指向我这个端口的看板窗口 —— 绝不碰用户自己那套。

    返回 [(pid, 标题, exe 路径)]；exe 路径用来确认"用的是不是被测目录里的程序"。
    """
    ps = ("Get-CimInstance Win32_Process -Filter \"Name='F1BoardWindow.exe'\" | "
          "Where-Object { $_.CommandLine -like '*:%s*' } | "
          "ForEach-Object { \"$($_.ProcessId)|$($_.ExecutablePath)\" }" % PORT)
    r = subprocess.run(["powershell", "-NoProfile", "-Command", ps],
                       capture_output=True, text=True)
    pids = {}
    for line in r.stdout.splitlines():
        line = line.strip()
        if "|" in line:
            pid, exe = line.split("|", 1)
            if pid.strip().isdigit():
                pids[int(pid.strip())] = exe.strip()
    found = []

    @ctypes.WINFUNCTYPE(wt.BOOL, wt.HWND, wt.LPARAM)
    def cb(h, l):
        if not u.IsWindowVisible(h):
            return True
        pid = wt.DWORD()
        u.GetWindowThreadProcessId(h, ctypes.byref(pid))
        if pid.value in pids:
            n = u.GetWindowTextLengthW(h)
            b = ctypes.create_unicode_buffer(n + 2)
            u.GetWindowTextW(h, b, n + 1)
            found.append((pid.value, b.value, pids[pid.value]))
        return True

    u.EnumWindows(cb, 0)
    return found


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("port", nargs="?", default="8790")
    ap.add_argument("--app", default=REPO,
                    help="要测的应用目录（默认本仓库；给发布包解压目录就验包）")
    args = ap.parse_args()

    global PORT
    PORT = args.port
    app = os.path.abspath(args.app)
    jar = find_jar(app)
    if jar is None:
        print("★ %s 里找不到 F1-RaceControl-Desktop.jar" % app)
        return 1
    print("被测应用: %s" % app)
    print("jar    : %s" % jar)

    tmp = os.path.join(REPO, "build", "watchdog_test")
    os.makedirs(tmp, exist_ok=True)
    cmd = os.path.join(tmp, "server.cmd")
    log = os.path.join(tmp, "server.log")
    with open(cmd, "w", encoding="gbk") as f:
        f.write("@echo off\r\n")
        f.write("title F1WATCHDOGTEST\r\n")
        # cwd 设成被应用目录：Boards.findBoardWindow 先看 jar 同级的 native/，
        # 所以这样才会用到**包里**的 F1BoardWindow.exe，而不是开发目录里那个。
        f.write('cd /d "%s"\r\n' % app)
        f.write('"%s" -Dfile.encoding=UTF-8 -jar "%s" --port %s --no-window\r\n'
                % (JAVA, jar, PORT))

    print("== 1. 用真控制台窗口启动服务器（--no-window，窗口全部靠弹出）==")
    p = subprocess.Popen(["cmd.exe", "/c", cmd], creationflags=subprocess.CREATE_NEW_CONSOLE)
    up = False
    for _ in range(60):
        time.sleep(0.5)
        try:
            if http("/api/health", timeout=3).get("ok"):
                up = True
                break
        except Exception:
            pass
    check("服务器起来了", up, "port %s" % PORT)
    if not up:
        subprocess.run(["taskkill", "/F", "/T", "/PID", str(p.pid)],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return 1

    print("== 2. 弹出两块看板 ==")
    for bid in ("tyres", "timing"):
        http("/api/popout/" + bid, "POST")
    time.sleep(9)
    ws = my_windows()
    check("两块小窗在", len(ws) == 2, [(w[1], w[2]) for w in ws])
    if ws:
        # 确认用的是被测目录里的程序，而不是开发目录里那个 ——
        # 否则"验收发布包"就是假的。
        inside = [w for w in ws if w[2].lower().startswith(app.lower())]
        check("窗口程序来自被测目录", len(inside) == len(ws),
              inside[0][2] if inside else ws[0][2])

    print("== 3. 服务器活着，等 22 秒（不该被误杀）==")
    time.sleep(22)
    check("窗口还在（看门狗不误杀）", len(my_windows()) == 2, len(my_windows()))

    print("== 4. 关掉控制台窗口（= 用户点右上角的 X）==")
    hw = find_window("F1WATCHDOGTEST")
    check("找到控制台窗口", hw is not None, hw)
    if hw:
        u.PostMessageW(hw, WM_CLOSE, 0, 0)
    time.sleep(30)
    left = my_windows()
    check("小窗自己关掉了", left == [], "%d 个残留 %s" % (len(left), left))
    gone = subprocess.run(["tasklist", "/FI", "PID eq %d" % p.pid, "/FO", "CSV", "/NH"],
                          capture_output=True, text=True).stdout
    check("服务器进程也没了", str(p.pid) not in gone)

    try:
        p.kill()
    except Exception:
        pass
    for w in my_windows():
        subprocess.run(["taskkill", "/F", "/PID", str(w[0])],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    print()
    print("全部通过 ✓" if ok else "★ 有失败项")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())