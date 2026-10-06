#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""编译看板原生窗口（F1BoardWindow.exe）。

产物：native/BoardWindow/bin/Release/net9.0-windows/F1BoardWindow.exe
      （开发/发布都由 Java 侧的 Boards.findBoardWindow() 自动找）

用法:
    python tools/build_native.py            # 编译
    python tools/build_native.py --publish  # 额外产出免安装运行时版本到 dist/native/
"""
import argparse
import os
import subprocess
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
PROJ_DIR = os.path.join(ROOT, "native", "BoardWindow")
PROJ = os.path.join(PROJ_DIR, "F1BoardWindow.csproj")
OUT_EXE = os.path.join(PROJ_DIR, "bin", "Release", "net9.0-windows",
                       "F1BoardWindow.exe")

# 本机实测的系统代理。★ 只有 nuget.org 直连失败时才用，不要无条件设：
# 环境如果是 TUN 模式，直连本来就能通，多设一个代理反而会引入故障点。
FALLBACK_PROXY = "http://127.0.0.1:7892"

RESTORE_HINTS = ("NU1301", "Unable to load the service index",
                 "无法加载源", "restore 失败", "NU1101", "NU1102")


def system_proxy():
    """从注册表读系统代理，读不到返回 None。不要猜端口。"""
    try:
        import winreg
        k = winreg.OpenKey(winreg.HKEY_CURRENT_USER,
                           r"Software\Microsoft\Windows\CurrentVersion"
                           r"\Internet Settings")
        try:
            enabled, _ = winreg.QueryValueEx(k, "ProxyEnable")
            server, _ = winreg.QueryValueEx(k, "ProxyServer")
        finally:
            winreg.CloseKey(k)
        if not enabled or not server:
            return None
        if "=" in server:                      # 形如 http=..;https=..
            parts = dict(p.split("=", 1) for p in server.split(";") if "=" in p)
            server = parts.get("https") or parts.get("http") or ""
        if not server:
            return None
        return server if server.startswith("http") else "http://" + server
    except Exception:
        return None


def run(cmd, env=None):
    p = subprocess.run(cmd, cwd=PROJ_DIR, stdout=subprocess.PIPE,
                       stderr=subprocess.STDOUT, universal_newlines=True,
                       encoding="utf-8", errors="replace", env=env)
    return p.returncode, p.stdout or ""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--publish", action="store_true",
                    help="产出免安装运行时（自包含）版本到 dist/native/")
    ap.add_argument("--self-contained", action="store_true",
                    help="--publish 时把 .NET 运行时也打进去（约 70-150MB）")
    args = ap.parse_args()

    if not os.path.isfile(PROJ):
        print("★ 找不到 %s" % PROJ, file=sys.stderr)
        return 2

    base_env = dict(os.environ)
    code, out = run(["dotnet", "build", "-c", "Release", "-v", "minimal"],
                    env=base_env)

    if code != 0 and any(h in out for h in RESTORE_HINTS):
        proxy = system_proxy() or FALLBACK_PROXY
        print("[native] 直连 nuget.org 取包失败，改走代理 %s 重试" % proxy)
        env = dict(base_env)
        env["HTTP_PROXY"] = proxy
        env["HTTPS_PROXY"] = proxy
        env["NO_PROXY"] = "localhost,127.0.0.1"
        code, out = run(["dotnet", "build", "-c", "Release", "-v", "minimal"],
                        env=env)

    if code != 0:
        print(out)
        print("★ 编译失败", file=sys.stderr)
        return 1

    if not os.path.isfile(OUT_EXE):
        print("★ 编译声称成功，但没找到 %s" % OUT_EXE, file=sys.stderr)
        return 1
    print("[native] %s  (%.1f KB)" % (OUT_EXE, os.path.getsize(OUT_EXE) / 1024.0))

    if args.publish:
        dist = os.path.join(ROOT, "dist", "native")
        env = dict(base_env)
        if not args.self_contained:
            # 免安装运行时 = 把 .NET 运行时也带上；不带就是"需要装 .NET 9"
            args.self_contained = True
        env = dict(base_env)
        proxy = system_proxy() or FALLBACK_PROXY
        cmd = ["dotnet", "publish", "-c", "Release", "-r", "win-x64",
               "--self-contained", "true",
               "-p:PublishSingleFile=true",
               "-p:IncludeNativeLibrariesForSelfExtract=true",
               "-o", dist]
        code, out = run(cmd, env=env)
        if code != 0:
            env2 = dict(env)
            env2["HTTP_PROXY"] = proxy
            env2["HTTPS_PROXY"] = proxy
            env2["NO_PROXY"] = "localhost,127.0.0.1"
            code, out = run(cmd, env=env2)
        if code != 0:
            print(out)
            print("★ publish 失败", file=sys.stderr)
            return 1
        exe = os.path.join(dist, "F1BoardWindow.exe")
        if os.path.isfile(exe):
            print("[native] 自包含版本: %s  (%.1f MB)"
                  % (exe, os.path.getsize(exe) / 1024.0 / 1024.0))
    return 0


if __name__ == "__main__":
    sys.exit(main())