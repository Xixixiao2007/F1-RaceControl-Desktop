#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""验证「窗口位置会不会因为强制关闭而丢」。

背景：从网页上点「收回」是强杀（Java 侧 `Process.destroy`），**不会**走
`FormClosing`。所以位置必须是 `F1BoardWindow` 自己在移动/缩放停下来之后写下的。
这个脚本就照着这条路径走一遍：

  1. 用一个独立的 key（testx）启动看板窗口，避免动到你自己记住的位置
  2. 用 `SetWindowPos` 把它挪到 (300,200) 并改成 700x500
  3. 等 1.6 秒（记位置的防抖是 1 秒）
  4. `taskkill /F` 强制结束它 —— 这就是网页上的「收回」
  5. 读回 `testx.json`，看记的是不是 (300,200) 700x500
  6. 不带 `--x/--y/--width/--height` 再启动一次，看窗口是否回到那个位置

用法（先起一个服务器，端口随便）：

    python tools\\build.py --native
    java -jar build\\F1-RaceControl-Desktop.jar --port 8759 --replay <某个.rclog> --no-window
    python tools\\test_window_memory.py 8759

跑完会把测试用的 `testx.json` 删掉，不动你自己的记录。
"""
import ctypes
import ctypes.wintypes as wt
import json
import os
import subprocess
import sys
import time

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
EXE = os.path.join(REPO, "native", "BoardWindow", "bin", "Release",
                   "net9.0-windows", "F1BoardWindow.exe")
KEY = "testx"
TITLE = "WINMEM_TEST"
MEM = os.path.join(os.environ["LOCALAPPDATA"], "F1-RaceControl-Desktop",
                   "windows", KEY + ".json")
URL = "http://127.0.0.1:%s/board/tyres" % (sys.argv[1] if len(sys.argv) > 1 else "8759")

u = ctypes.windll.user32
SWP_NOZORDER = 0x0004
SWP_NOACTIVATE = 0x0010

ok = True


def check(label, cond, extra=""):
    global ok
    if not cond:
        ok = False
    print("  [%s] %-38s %s" % ("OK" if cond else "!!", label, extra))


def find(title):
    """按标题找顶层窗口，返回 [(hwnd, pid, x, y, w, h)]。"""
    found = []

    @ctypes.WINFUNCTYPE(wt.BOOL, wt.HWND, wt.LPARAM)
    def cb(h, l):
        if not u.IsWindowVisible(h):
            return True
        n = u.GetWindowTextLengthW(h)
        if n <= 0:
            return True
        b = ctypes.create_unicode_buffer(n + 1)
        u.GetWindowTextW(h, b, n + 1)
        if b.value == title:
            pid = wt.DWORD()
            u.GetWindowThreadProcessId(h, ctypes.byref(pid))
            r = wt.RECT()
            u.GetWindowRect(h, ctypes.byref(r))
            found.append((h, pid.value, r.left, r.top,
                          r.right - r.left, r.bottom - r.top))
        return True

    u.EnumWindows(cb, 0)
    return found


def launch(extra):
    p = subprocess.Popen([EXE, "--url", URL, "--title", TITLE, "--key", KEY] + extra,
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    for _ in range(40):
        time.sleep(0.5)
        w = find(TITLE)
        if w:
            return p, w[0]
    return p, None


def kill(pid):
    subprocess.run(["taskkill", "/F", "/PID", str(pid)],
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


if not os.path.isfile(EXE):
    print("★ 找不到 %s\n  先跑 python tools\\build.py --native" % EXE)
    sys.exit(1)

if os.path.exists(MEM):
    os.remove(MEM)
    print("  先删掉旧的 %s" % MEM)

print("== 1. 带 --x 100 --y 100 --width 620 --height 420 启动 ==")
p1, w1 = launch(["--width", "620", "--height", "420", "--x", "100", "--y", "100"])
check("窗口已出现", w1 is not None, w1)
if not w1:
    print("★ 窗口没起来 —— 服务器没在跑？端口对吗？")
    sys.exit(1)
print("       起始: x=%d y=%d %dx%d" % w1[2:])

print("== 2. 挪到 (300,200) 并改成 700x500 ==")
u.SetWindowPos(w1[0], 0, 300, 200, 700, 500, SWP_NOZORDER | SWP_NOACTIVATE)
time.sleep(1.6)                      # 防抖 1 秒，多等一点
w1b = find(TITLE)[0]
check("移动生效", w1b[2] == 300 and w1b[3] == 200 and w1b[4] == 700,
      "x=%d y=%d %dx%d" % w1b[2:])
saved = None
if os.path.exists(MEM):
    with open(MEM, encoding="utf-8") as f:
        saved = json.load(f)
check("停手后已自动写盘（没等关闭）", saved is not None,
      saved if saved else "还没有 " + MEM)

print("== 3. 强杀它（这就是网页上的「收回」）==")
kill(w1b[1])
time.sleep(2.0)
check("进程已消失", find(TITLE) == [])
if saved:
    check("记下的正是最后的位置",
          saved.get("x") == 300 and saved.get("y") == 200
          and saved.get("w") == 700 and saved.get("h") == 500, saved)

print("== 4. 不带任何位置参数再启动一次 ==")
p2, w2 = launch([])
check("窗口已出现", w2 is not None, w2)
if w2:
    print("       回到: x=%d y=%d %dx%d" % w2[2:])
    check("回到了强杀前的位置",
          w2[2] == 300 and w2[3] == 200 and w2[4] == 700 and w2[5] == 500,
          "期望 x=300 y=200 700x500")
    kill(w2[1])

for p in (p1, p2):
    try:
        p.kill()
    except Exception:
        pass
if os.path.exists(MEM):
    os.remove(MEM)
    print("  清掉测试用的 %s（没动你自己的记录）" % MEM)

print()
print("全部通过 ✓" if ok else "★ 有失败项")
sys.exit(0 if ok else 1)