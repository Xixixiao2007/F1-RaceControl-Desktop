#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""打一个免安装的发布包（zip）。

产物：dist/F1-RaceControl-Desktop-<版本>.zip
      解压后双击「启动.cmd」就能用。

为什么要单独一个脚本、而不是直接 zip 那几个构建产物：
.NET 程序**不是一个 exe 就完事** —— 框架依赖模式下还需要
F1BoardWindow.dll / .deps.json / .runtimeconfig.json 和 WebView2 的几个
managed DLL + WebView2Loader.dll。少一个就是"窗口一闪就没"，
所以这里把要带的东西写死在清单里，并**逐个核对存在性**，缺了就报错而不是
打出一个装不起来的包。

用法:
    python tools/make_dist.py                 # 编译 + 打包
    python tools/make_dist.py --no-build      # 用现有产物打包
"""
import argparse
import hashlib
import os
import shutil
import subprocess
import sys
import zipfile

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DIST = os.path.join(ROOT, "dist")
NATIVE_BIN = os.path.join(ROOT, "native", "BoardWindow", "bin", "Release",
                          "net9.0-windows")
JAR = os.path.join(ROOT, "build", "F1-RaceControl-Desktop.jar")

# 打包时整目录复制原生产物（见下），但**这几个必须存在**，缺了直接报错。
#
# 为什么不再手写"要带哪些文件"的完整清单：第一版就漏了
# runtimes/win-x64/native/WebView2Loader.dll（.NET 把它放在 runtimes/ 下面，
# 不在根目录）。手写清单必然会和构建产物漂移，而漏一个的后果是
# "窗口一闪就没"，极难排查。所以复制用整目录，清单只用于**核对关键项**。
REQUIRED_NATIVE = [
    "F1BoardWindow.exe",
    "F1BoardWindow.dll",
    "F1BoardWindow.deps.json",
    "F1BoardWindow.runtimeconfig.json",
    "Microsoft.Web.WebView2.Core.dll",
    "Microsoft.Web.WebView2.WinForms.dll",
    os.path.join("runtimes", "win-x64", "native", "WebView2Loader.dll"),
]

# 不带进包：调试符号和 XML 文档注释
SKIP_SUFFIX = (".pdb", ".xml")

LAUNCH = """@echo off
chcp 65001 >nul
cd /d "%~dp0"
title F1 Race Control

where java >nul 2>nul
if errorlevel 1 (
  echo.
  echo   没有找到 Java。请先装一个 JDK 或 JRE 8 以上：
  echo   https://adoptium.net/
  echo.
  pause
  exit /b 1
)

echo 正在启动 F1 Race Control ...
echo （关掉这个窗口或按 Ctrl+C 就会退出）
echo.
java -jar F1-RaceControl-Desktop.jar --open --all-boards %*
echo.
echo 已退出。
pause
"""

LAUNCH_LAN = """@echo off
chcp 65001 >nul
cd /d "%~dp0"
title F1 Race Control（允许局域网访问）

echo 这一步会弹一次"用户账户控制"，问你要不要允许修改防火墙规则。
echo 允许之后，同一个 Wi-Fi 下的手机 / 平板 / iPhone 就能打开看板。
echo.

java -jar F1-RaceControl-Desktop.jar --firewall --open --all-boards %*
echo.
echo 已退出。
pause
"""

README_TXT = """F1-RaceControl-Desktop {version}
===============================

这是什么
--------
F1 赛事控制消息的 Windows 客户端。直连 F1 官方公开数据流，
不需要 Home Assistant、不需要令牌。

怎么用
------
双击「启动.cmd」即可。会打开主界面和全部 9 块看板窗口。

  另外双击「启动并允许局域网访问.cmd」会顺便加一条防火墙规则，
  之后同一个 Wi-Fi 下的手机 / iPhone 用浏览器就能看，界面完全一样。
  （这一步会弹一次 UAC 询问，允许一次就行。）

看板窗口
--------
每块看板都是独立的窗口，可以随便拖动、拉伸缩放，任务栏里各自有图标，
和微信/QQ 的窗口一样。窗口位置和大小会被记住，摆一次就够了。
F11 全屏，Esc 退出全屏。

没有比赛的时候
--------------
可以下载 bahrain2026_race_mid.rclog，然后这样启动：

  启动.cmd --replay bahrain2026_race_mid.rclog --speed 200

就能把一场真实比赛按倍速重演一遍，用来试布局、试手机连接。

运行环境
--------
  Java 8 或更高     必须（没有就启动不了）
  .NET 9 运行时     可选。没有的话看板窗口会退化成 Edge 无地址栏窗口，
                    功能一样，只是任务栏图标是浏览器而不是本程序。
  WebView2 运行时   Win11 和较新的 Win10 自带；没有的话原生窗口会提示。

详细说明见 https://github.com/Xixixiao2007/F1-RaceControl-Desktop
"""


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def version():
    return open(os.path.join(ROOT, "VERSION"), encoding="utf-8").read().strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--no-build", action="store_true")
    args = ap.parse_args()

    v = version()
    if not args.no_build:
        code = subprocess.call([sys.executable, os.path.join(HERE, "build.py"),
                                "--native"], cwd=ROOT)
        if code != 0:
            print("★ 构建失败，打包中止", file=sys.stderr)
            return code

    # ---- 核对关键文件：缺了就报错，绝不打一个装不起来的包
    missing = []
    if not os.path.isfile(JAR):
        missing.append(JAR)
    for rel in REQUIRED_NATIVE:
        p = os.path.join(NATIVE_BIN, rel)
        if not os.path.isfile(p):
            missing.append(p)
    if missing:
        for m in missing:
            print("★ 缺少：%s" % m, file=sys.stderr)
        print("  先跑 python tools/build.py --native", file=sys.stderr)
        return 1

    stage = os.path.join(DIST, "F1-RaceControl-Desktop-%s" % v)
    if os.path.isdir(stage):
        shutil.rmtree(stage)
    os.makedirs(stage)

    shutil.copy2(JAR, stage)
    # 整目录复制原生产物（保留 runtimes/ 的层次），跳过 pdb 和 xml
    copied = 0
    for dirpath, _dirnames, filenames in os.walk(NATIVE_BIN):
        for fn in filenames:
            if fn.lower().endswith(SKIP_SUFFIX):
                continue
            src = os.path.join(dirpath, fn)
            rel = os.path.relpath(src, NATIVE_BIN)
            dst = os.path.join(stage, rel)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copy2(src, dst)
            copied += 1

    def write(name, text):
        with open(os.path.join(stage, name), "w", encoding="utf-8",
                  newline="\r\n") as fh:
            fh.write(text.replace("\n", "\r\n"))

    # .cmd 必须是 CRLF + ANSI 兼容；中文注释会出问题，所以正文用 echo 输出
    write("启动.cmd", LAUNCH)
    write("启动并允许局域网访问.cmd", LAUNCH_LAN)
    write("使用说明.txt", README_TXT.format(version=v))

    zpath = os.path.join(DIST, "F1-RaceControl-Desktop-%s.zip" % v)
    if os.path.isfile(zpath):
        os.remove(zpath)
    names = []
    for dirpath, _dirnames, filenames in os.walk(stage):
        for fn in filenames:
            full = os.path.join(dirpath, fn)
            names.append(os.path.relpath(full, stage))
    names.sort()
    with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for n in names:
            z.write(os.path.join(stage, n),
                    "F1-RaceControl-Desktop-%s/%s" % (v, n.replace(os.sep, "/")))

    print("[dist] %s" % zpath)
    print("[dist] %.1f KB（原生窗口共 %d 个文件）"
          % (os.path.getsize(zpath) / 1024.0, copied))
    print("[dist] SHA256 %s" % sha256(zpath))
    print("[dist] 包内文件：")
    for n in names:
        print("         %-48s %8.1f KB"
              % (n, os.path.getsize(os.path.join(stage, n)) / 1024.0))
    return 0


if __name__ == "__main__":
    sys.exit(main())