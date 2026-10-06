#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
编译桌面版。

产物：build/F1-RaceControl-Desktop.jar（含 web/ 界面资源，单文件自带界面）。

用法:
    python tools/build.py              # 编译 + 打 jar
    python tools/build.py --run        # 编译后直接跑（参数跟 --run 后面）
    python tools/build.py --clean
"""
import argparse
import glob
import io
import os
import re
import shutil
import subprocess
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SHARED = os.path.join(ROOT, "shared")
SRC = os.path.join(ROOT, "src")
WEB = os.path.join(ROOT, "web")
BUILD = os.path.join(ROOT, "build")
CLASSES = os.path.join(BUILD, "classes")
JAR = os.path.join(BUILD, "F1-RaceControl-Desktop.jar")

# 这台机器上实测存在的 JDK。找不到再退回 JAVA_HOME / PATH。
KNOWN_JDKS = [
    r"C:\Program Files\Android\jdk\jdk-8.0.302.8-hotspot\jdk8u302-b08",
    r"C:\Program Files (x86)\Android\openjdk\jdk-17.0.14",
    r"C:\Program Files\JetBrains\PyCharm 2025.3.2.1\jbr",
]


def exe(name):
    return name + (".exe" if os.name == "nt" else "")


def find_jdk():
    for env in ("HAF1_JDK", "JAVA_HOME"):
        p = os.environ.get(env)
        if p and os.path.isfile(os.path.join(p, "bin", exe("javac"))):
            return p
    for p in KNOWN_JDKS:
        if os.path.isfile(os.path.join(p, "bin", exe("javac"))):
            return p
    java_home = os.path.dirname(os.path.dirname(shutil.which("javac") or ""))
    if java_home and os.path.isfile(os.path.join(java_home, "bin", exe("javac"))):
        return java_home
    return None


def collect(root):
    out = []
    for dirpath, _d, files in os.walk(root):
        for f in sorted(files):
            if f.endswith(".java"):
                out.append(os.path.join(dirpath, f))
    return out


def run(cmd, **kw):
    p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                       universal_newlines=True, encoding="utf-8",
                       errors="replace", **kw)
    return p.returncode, p.stdout or ""


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--clean", action="store_true")
    ap.add_argument("--run", action="store_true", help="编译后直接运行")
    ap.add_argument("--native", action="store_true",
                    help="顺便编译看板原生窗口（F1BoardWindow.exe）")
    ap.add_argument("--jar-only", action="store_true", help="跳过编译，只重新打 jar")
    ap.add_argument("rest", nargs="*", help="--run 时传给程序的参数")
    args = ap.parse_args()

    if args.clean:
        shutil.rmtree(BUILD, ignore_errors=True)
        print("已清掉 build/")
        if not args.run:
            return 0

    jdk = find_jdk()
    if jdk is None:
        print("★ 找不到 JDK。装一个 JDK 8~17，或设 HAF1_JDK / JAVA_HOME。",
              file=sys.stderr)
        return 2

    # ★ 版本号闸门：三处必须一致，否则发出去的东西自己都对不上。
    #   踩过一次真实的坑（安卓仓库）：改了源码忘了升版本号，Release 里的包
    #   和 tag 说的不是一回事。这里由构建脚本直接拦。
    v = version()
    dm = os.path.join(SRC, "com", "haf1", "racecontrol", "desktop",
                      "DesktopMain.java")
    if not os.path.isfile(dm):
        print("★ 找不到 %s" % dm, file=sys.stderr)
        return 2
    m = re.search(r'VERSION\s*=\s*"([^"]+)"',
                  io.open(dm, encoding="utf-8").read())
    if not m:
        print("★ DesktopMain 里找不到 VERSION 常量", file=sys.stderr)
        return 2
    if m.group(1) != v:
        print("★ 版本号不一致：VERSION 文件是 %s，DesktopMain.VERSION 是 %s"
              % (v, m.group(1)), file=sys.stderr)
        return 1
    cl = os.path.join(ROOT, "CHANGELOG.md")
    if os.path.isfile(cl):
        changelog = io.open(cl, encoding="utf-8").read()
        if ("## %s" % v) not in changelog:
            print("★ CHANGELOG.md 里没有版本 %s 的条目（文档要跟着发版走）" % v,
                  file=sys.stderr)
            return 1
    print("[build] 版本 %s（VERSION / DesktopMain / CHANGELOG 一致）" % v)
    javac = os.path.join(jdk, "bin", exe("javac"))
    java = os.path.join(jdk, "bin", exe("java"))
    jar = os.path.join(jdk, "bin", exe("jar"))
    print("[build] JDK: %s" % jdk)

    if os.path.isdir(SHARED) and not os.path.isfile(
            os.path.join(SHARED, "PROVENANCE.json")):
        print("★ shared/ 里没有 PROVENANCE.json —— 先跑 python tools/sync_shared.py",
              file=sys.stderr)
        return 2

    if not args.jar_only:
        sources = []
        sources += collect(os.path.join(SHARED, "stub"))
        sources += collect(os.path.join(SHARED, "com"))
        sources += collect(SRC)
        if not sources:
            print("★ 一个 .java 都没有", file=sys.stderr)
            return 2

        shutil.rmtree(CLASSES, ignore_errors=True)
        os.makedirs(CLASSES)
        argfile = os.path.join(BUILD, "javac.args")
        os.makedirs(BUILD, exist_ok=True)
        io.open(argfile, "w", encoding="utf-8", newline="\n").write(
            "\n".join('"%s"' % p.replace("\\", "/") for p in sources))

        code, out = run([javac, "-encoding", "UTF-8", "-source", "1.8",
                         "-target", "1.8", "-nowarn",
                         "-J-Duser.language=en", "-J-Duser.country=US",
                         "-d", CLASSES, "@" + argfile])
        if code != 0:
            print(out)
            print("★ 编译失败", file=sys.stderr)
            return 1
        print("[build] 编译 %d 个源文件" % len(sources))

    # ★ web/ 必须打进 jar 的 /web/ 下 —— WebServer 找不到磁盘上的 web/
    #   时回退到 classpath 取 "/web/<rel>"。发布形态就是靠这个。
    if os.path.isdir(WEB):
        dst = os.path.join(CLASSES, "web")
        shutil.rmtree(dst, ignore_errors=True)
        shutil.copytree(WEB, dst)
        print("[build] 把 web/ 打进 jar")

    manifest = os.path.join(BUILD, "MANIFEST.MF")
    io.open(manifest, "w", encoding="utf-8", newline="\n").write(
        "Manifest-Version: 1.0\r\n"
        "Main-Class: com.haf1.racecontrol.desktop.DesktopMain\r\n"
        "Implementation-Title: F1-RaceControl-Desktop\r\n"
        "Implementation-Version: %s\r\n"
        "\r\n" % version())
    code, out = run([jar, "cfm", JAR, manifest, "-C", CLASSES, "."])
    if code != 0:
        print(out)
        print("★ 打 jar 失败", file=sys.stderr)
        return 1
    size = os.path.getsize(JAR)
    print("[build] %s  (%.1f KB)" % (JAR, size / 1024.0))

    if args.native:
        # 原生窗口不是必须的（缺了会降级用 Edge 开），所以它失败
        # **不该** 让整个构建失败 —— 但要明确报出来。
        code, out = run([sys.executable, os.path.join(HERE, "build_native.py")],
                        cwd=ROOT)
        sys.stdout.write(out)
        if code != 0:
            print("[build] ★ 原生窗口没编成（不影响 jar；运行时会给 Edge 兜底）",
                  file=sys.stderr)

    if args.run:
        print("[run] 启动……")
        print()
        # ★ 不捕获输出：要让用户直接看到程序的控制台（含网址、UAC 结果）
        return subprocess.call([java, "-Dfile.encoding=UTF-8", "-jar", JAR]
                               + list(args.rest))
    return 0


def version():
    p = os.path.join(ROOT, "VERSION")
    if os.path.isfile(p):
        return io.open(p, encoding="utf-8").read().strip()
    return "0.0.0"


if __name__ == "__main__":
    sys.exit(main())