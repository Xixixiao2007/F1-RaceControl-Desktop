#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
把安卓版（HA-F1-RaceControl）的产品源码同步到本仓库的 shared/ 下。

## 为什么不是"重写一份"
桌面客户端要"和安卓相同功能"。重写一份逻辑必然漂移（Translator 895 行、
F1Feed 591 行，两处维护迟早对不上），而且**无法证明**功能相同。
所以这里同步的是安卓那份**源码本体**，编译时和安卓构建吃的是同一批文件。

已经验证过：桌面 JDK 8 能编译并真实连上 F1 官方流
（dsh/tools/f1_java_smoke.py 就是拿这套源码跑的）。

## 漂移怎么防
每次同步记录 app 仓库的 commit 和每个文件的 SHA256 到 PROVENANCE.json：

    python tools/sync_shared.py            # 同步（会覆盖 shared/）
    python tools/sync_shared.py --check    # 校验 shared/ 与记录是否一致
    python tools/sync_shared.py --drift    # 看安卓那边是否已经跑在前面

`--check` 不联网、不看 app 仓库，适合放进测试套件。
"""
import argparse
import datetime
import hashlib
import io
import json
import os
import shutil
import subprocess
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SHARED = os.path.join(ROOT, "shared")
PROV = os.path.join(SHARED, "PROVENANCE.json")

DEFAULT_APP = os.path.join(os.path.dirname(ROOT), "HA-F1-RaceControl")
APP_SRC = os.path.join("app", "src", "com", "haf1", "racecontrol")
STUB_DIR = os.path.join("tools", "tztest", "stub")

# 参与桌面的产品源码。挑选标准：**不依赖 android.* 运行时**。
# 这份清单和安卓仓库 tools/run_tests.py 的 TESTED_SOURCES 基本重合 ——
# 那不是巧合：能在桌面单测里跑的纯逻辑，正好也能在桌面客户端里跑。
SHARED_SOURCES = [
    "F1Client.java",      # 直连 F1：negotiate / 握手 / 订阅 / 重连
    "F1Feed.java",        # 增量深合并 -> 状态
    "F1Layout.java",      # 界面几何（圆环 / 轮胎面板 / 旗语栏），纯算术
    "FeedSource.java",    # 数据源接口（真流 / 回放）
    "ReplayClient.java",  # 回放 .rclog（和安卓同一个文件格式）
    "WsFrame.java",       # RFC6455 帧编解码
    "RaceMessage.java",   # 消息解析与去重键
    "MessageStore.java",  # 去重 / 容量 / 序列化
    "TrackState.java",    # 优先级状态机
    "Classifier.java",    # 旗语分类
    "AlertGate.java",     # 聚类 / 升级 / 冷却
    "Translator.java",    # 中文简述（最大的一张表）
    "HaClient.java",      # ISO8601 解析（RaceMessage 依赖它）
    "Prefs.java",         # 过滤与钳制
]


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 16), b""):
            h.update(chunk)
    return h.hexdigest()


def sh(args, cwd=None):
    p = subprocess.run(args, cwd=cwd, stdout=subprocess.PIPE,
                       stderr=subprocess.STDOUT, universal_newlines=True,
                       encoding="utf-8", errors="replace")
    return p.returncode, (p.stdout or "").strip()


def app_commit(app):
    code, out = sh(["git", "rev-parse", "HEAD"], cwd=app)
    if code != 0:
        return None, None, None
    full = out
    code2, date = sh(["git", "log", "-1", "--format=%cI"], cwd=app)
    code3, subj = sh(["git", "log", "-1", "--format=%s"], cwd=app)
    code4, dirty = sh(["git", "status", "--porcelain"], cwd=app)
    return full, (date if code2 == 0 else ""), {
        "subject": subj if code3 == 0 else "",
        "dirty": bool(dirty) if code4 == 0 else None,
    }


def collect_plan(app):
    """返回 [(绝对源路径, 相对 shared/ 的目标路径)]。"""
    plan = []
    src = os.path.join(app, APP_SRC)
    for name in SHARED_SOURCES:
        p = os.path.join(src, name)
        if not os.path.isfile(p):
            print("★ 安卓仓库里找不到 %s（%s）" % (name, p))
            return None
        plan.append((p, os.path.join("com", "haf1", "racecontrol", name)))
    stub = os.path.join(app, STUB_DIR)
    if not os.path.isdir(stub):
        print("★ 找不到 stub 目录: %s" % stub)
        return None
    for dirpath, _d, files in os.walk(stub):
        for f in sorted(files):
            if not f.endswith(".java"):
                continue
            full = os.path.join(dirpath, f)
            rel = os.path.relpath(full, stub)
            plan.append((full, os.path.join("stub", rel)))
    return plan


def do_sync(app):
    plan = collect_plan(app)
    if plan is None:
        return 1
    if os.path.isdir(SHARED):
        shutil.rmtree(SHARED)
    files = {}
    for src, rel in plan:
        dst = os.path.join(SHARED, rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copyfile(src, dst)
        files[rel.replace("\\", "/")] = sha256(dst)

    full, date, extra = app_commit(app)
    prov = {
        "app_repo": os.path.basename(os.path.abspath(app)),
        "app_commit": full,
        "app_commit_short": (full or "")[:7],
        "app_commit_date": date,
        "app_subject": (extra or {}).get("subject", ""),
        "app_worktree_dirty": (extra or {}).get("dirty"),
        "synced_at": datetime.datetime.now().astimezone().isoformat(timespec="seconds"),
        "file_count": len(files),
        "files": files,
    }
    os.makedirs(SHARED, exist_ok=True)
    io.open(PROV, "w", encoding="utf-8", newline="\n").write(
        json.dumps(prov, ensure_ascii=False, indent=2, sort_keys=True) + "\n")

    print("同步 %d 个文件到 shared/" % len(files))
    print("  来源 app 仓库 : %s" % prov["app_repo"])
    print("  来源 commit   : %s  %s" % (prov["app_commit_short"],
                                        prov["app_commit_date"]))
    print("  来源提交说明  : %s" % prov["app_subject"])
    if prov["app_worktree_dirty"]:
        print("  ★ 注意：安卓仓库工作区有未提交改动 —— "
              "同步下来的是工作区当前内容，不是 commit 的内容")
    print("  产品源码 %d 个 + 桩 %d 个"
          % (len(SHARED_SOURCES), len(files) - len(SHARED_SOURCES)))
    return 0


def do_check():
    if not os.path.isfile(PROV):
        print("★ 没有 %s，先跑一次 python tools/sync_shared.py" % PROV)
        return 2
    prov = json.loads(io.open(PROV, encoding="utf-8").read())
    bad = []
    for rel, want in sorted(prov["files"].items()):
        p = os.path.join(SHARED, rel.replace("/", os.sep))
        if not os.path.isfile(p):
            bad.append((rel, "缺失"))
        elif sha256(p) != want:
            bad.append((rel, "内容变了"))
    print("校验 shared/：%d 个文件" % len(prov["files"]))
    print("  来源 commit : %s" % prov["app_commit_short"])
    if bad:
        for rel, why in bad:
            print("  ★ %s —— %s" % (rel, why))
        print("★ %d 个文件对不上：有人手工改了 shared/，或者没跑同步就改了。"
              % len(bad))
        return 1
    print("  全部一致 ✓")
    return 0


def do_drift(app):
    if not os.path.isfile(PROV):
        print("★ 没有 %s" % PROV)
        return 2
    prov = json.loads(io.open(PROV, encoding="utf-8").read())
    plan = collect_plan(app)
    if plan is None:
        return 1
    moved = []
    for src, rel in plan:
        key = rel.replace("\\", "/")
        want = prov["files"].get(key)
        if want is None:
            moved.append((key, "新增"))
        elif sha256(src) != want:
            moved.append((key, "安卓那边改了"))
    full, date, extra = app_commit(app)
    print("本仓库 shared/ 来源: %s (%s)" % (prov["app_commit_short"],
                                            prov["app_commit_date"]))
    print("安卓仓库当前 HEAD  : %s (%s)  %s"
          % ((full or "?")[:7], date, (extra or {}).get("subject", "")))
    if not moved:
        print("✓ 没有漂移，shared/ 与安卓当前源码一致")
        return 0
    print("★ %d 个文件漂移了（跑 sync_shared.py 重新同步）：" % len(moved))
    for key, why in moved:
        print("   %-52s %s" % (key, why))
    return 1


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--app", default=DEFAULT_APP, help="安卓仓库根目录")
    ap.add_argument("--check", action="store_true", help="校验 shared/ 与记录一致")
    ap.add_argument("--drift", action="store_true", help="对比安卓仓库是否已跑在前面")
    args = ap.parse_args()

    if args.check:
        return do_check()
    if args.drift:
        return do_drift(args.app)
    return do_sync(args.app)


if __name__ == "__main__":
    sys.exit(main())