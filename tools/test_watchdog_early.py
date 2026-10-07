#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""回归测试：服务器在窗口**还没成功探到过一次**时就死掉，窗口也必须自己关。

这是 0.1.4 留下的洞。看门狗原来有一条门槛："必须连着过一次才开始计数" ——
目的是防止窗口比服务器先起来时被误杀。但这条门槛**没有上限**：
服务器要是在第一次成功探测之前就消失（或第一次探测因任何原因没成功），
计数永远不会开始，这个窗口就永远留着了 —— 正是用户报的
"服务器关了还有小窗没关"。

两段，一段确定性、一段端到端：

  一、**确定性的那半**：直接开一个窗口，指向一个**根本没有服务器**的端口。
      这样"从未成功探测过"是必然的，不是碰运气。修好后它会在约 30 秒内自己关
      （没见过成功时用 10 次 x 3 秒的耐心兜底）；老代码则永远不倒。
  二、端到端那半：起真实实例（带原生主窗口），窗口一出现就杀掉服务器。

用法：python tools\\test_watchdog_early.py [端口]
"""
import argparse
import ctypes
import ctypes.wintypes as wt
import os
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.environ.get("JAVA_HOME") or ""
JAVA = (os.path.join(JAVA, "bin", "java.exe") if JAVA else
        (r"C:\Program Files\Android\jdk\jdk-8.0.302.8-hotspot\jdk8u302-b08\bin\java.exe"))
if not os.path.isfile(JAVA):
    JAVA = "java"
BOARD_EXE = os.path.join(REPO, "native", "BoardWindow", "bin", "Release",
                         "net9.0-windows", "F1BoardWindow.exe")
u = ctypes.windll.user32
ok = True


def check(label, cond, extra=""):
    global ok
    if not cond:
        ok = False
    print("  [%s] %-42s %s" % ("OK" if cond else "!!", label, extra))


def my_windows(port):
    ps = ("Get-CimInstance Win32_Process -Filter \"Name='F1BoardWindow.exe'\" | "
          "Where-Object { $_.CommandLine -like '*:%d*' } | "
          "ForEach-Object { $_.ProcessId }" % port)
    r = subprocess.run(["powershell", "-NoProfile", "-Command", ps],
                       capture_output=True, text=True)
    return [int(x) for x in r.stdout.split() if x.strip().isdigit()]


def free_port(start):
    for p in range(start, start + 60):
        s = socket.socket()
        try:
            s.bind(("127.0.0.1", p))
            return p          # 绑过就关掉：这个端口上什么都没有
        except OSError:
            pass
        finally:
            s.close()
    raise RuntimeError("没端口")


def phase_one():
    """确定性：窗口对着一个没有任何服务器的端口，必须自己关。"""
    print("== 一、确定性：窗口指向一个根本没有服务器的端口 ==")
    dead = free_port(9060)
    key = "testnever"
    log = open(os.path.join(REPO, "build", "watchdog_never.log"), "wb")
    p = subprocess.Popen([BOARD_EXE, "--url", "http://127.0.0.1:%d/" % dead,
                          "--title", "F1WATCHDOGNEVER", "--key", key,
                          "--width", "400", "--height", "300"],
                         cwd=REPO, stdout=log, stderr=subprocess.STDOUT)
    time.sleep(4)
    check("窗口开起来了", p.poll() is None)
    if p.poll() is not None:
        log.close()
        return
    gone = None
    for i in range(50):
        time.sleep(1)
        if p.poll() is not None:
            gone = i + 1
            break
    check("★ 从未探到过成功也自己关了", gone is not None,
          ("%d 秒" % gone) if gone else "50 秒都没关 —— 就是那个洞")
    check("是有限耐心（<=45 秒）", gone is not None and gone <= 45,
          ("%d 秒" % gone) if gone else "")
    if p.poll() is None:
        p.kill()
    # 这个测试用的 key 会留下自己的位置记录，清掉
    mem = os.path.join(os.environ.get("LOCALAPPDATA", ""), "F1-RaceControl-Desktop",
                       "windows", key + ".json")
    if os.path.isfile(mem):
        os.remove(mem)
    log.close()


def phase_two(port):
    """端到端：真实实例，窗口一出现就杀服务器。"""
    print("== 二、端到端：窗口一出现就杀掉服务器 ==")
    jar = os.path.join(REPO, "build", "F1-RaceControl-Desktop.jar")
    rclog = os.path.join(os.path.dirname(REPO), "HA-F1-RaceControl", "tools",
                         "mock_data", "bahrain2026_race_mid.rclog")
    print("   被测端口 %d" % port)
    log = open(os.path.join(REPO, "build", "watchdog_early.log"), "wb")
    p = subprocess.Popen([JAVA, "-Dfile.encoding=UTF-8", "-jar", jar,
                          "--port", str(port), "--open", "--replay", rclog,
                          "--speed", "60"], cwd=REPO,
                         stdout=log, stderr=subprocess.STDOUT)
    ws = []
    for _ in range(160):
        time.sleep(0.25)
        ws = my_windows(port)
        if ws:
            break
    check("窗口出现了", ws != [], ws)
    # 说明白：这时候窗口大概 1 秒大，第一次探测在 3 秒后 —— 大概率还没探到过，
    # 但**不能保证**（查询本身要花时间）。所以"从未成功"的确定性证明在上面那半。
    p.kill()
    o = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        o.open("http://127.0.0.1:%d/api/health" % port, timeout=3)
        check("服务器杀掉了", False, "还能连上")
    except urllib.error.URLError:
        check("服务器杀掉了", True)
    gone = None
    for i in range(60):
        time.sleep(1)
        if not my_windows(port):
            gone = i + 1
            break
    check("窗口自己关了", gone is not None,
          ("%d 秒" % gone) if gone else "60 秒都没关")
    for pid in my_windows(port):
        subprocess.run(["taskkill", "/F", "/PID", str(pid)],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    log.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("port", nargs="?", default="0")
    args = ap.parse_args()
    port = int(args.port) if args.port != "0" else free_port(9020)
    phase_one()
    phase_two(port)
    print()
    print("全部通过 ✓" if ok else "★ 有失败项")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())