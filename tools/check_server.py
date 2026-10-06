#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""打一遍桌面版 Web 服务器的接口，确认全链路通了。"""
import json
import socket
import sys
import time
import urllib.error
import urllib.request

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8731
BASE = "http://127.0.0.1:%d" % PORT
# ★ 必须绕开环境里的 HTTP(S)_PROXY：走代理连 127.0.0.1 会被打回来
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def get(path, raw=False):
    with OPENER.open(BASE + path, timeout=20) as r:
        data = r.read()
        return r.status, (data if raw else data.decode("utf-8"))


ok = True


def check(label, cond, extra=""):
    global ok
    if not cond:
        ok = False
    print("  [%s] %-34s %s" % ("OK" if cond else "!!", label, extra))


print("== 1. /api/health ==")
st, body = get("/api/health")
h = json.loads(body)
check("HTTP 200", st == 200, st)
check("端口已绑定", h.get("port") == PORT, h.get("port"))
check("有数据在推", (h.get("updates") or 0) > 0, "updates=%s" % h.get("updates"))
print("       error=%r" % h.get("error"))

print()
print("== 2. /api/state（界面实际用的那一份）==")
st, body = get("/api/state")
s = json.loads(body)
size = len(body)
check("HTTP 200", st == 200, st)
check("体积合理（<200KB）", size < 200000, "%.1f KB" % (size / 1024.0))

race = s.get("race") or {}
track = s.get("track") or {}
cars = s.get("cars") or []
msgs = s.get("messages") or []
bar = s.get("bar") or []
ring = s.get("ring") or {}

print("       会议/赛道 : %s / %s" % (race.get("meeting"), race.get("circuit")))
print("       环节/状态 : %s / %s   圈 %s/%s" % (
    race.get("session"), race.get("status"), race.get("lap"), race.get("totalLaps")))
print("       轨道状态  : %s (level=%s)  %s" % (
    track.get("label"), track.get("level"), track.get("detail") or ""))
print("       前三      : %s" % (race.get("topThree") or []))

check("有车手列表", len(cars) > 0, "%d 位" % len(cars))
check("有赛事消息", len(msgs) > 0, "%d 条" % len(msgs))
check("区段数 > 0", (ring.get("count") or 0) > 0, ring.get("count"))
check("颜色是安卓下发的 ARGB", isinstance(track.get("color"), int),
      "%s / %s" % (track.get("color"), track.get("textColor")))

if cars:
    c = cars[0]
    need = ["number", "tla", "team", "position", "compoundCn", "tyreLaps",
            "pitStops", "gap", "interval", "bestLap", "label", "inPit"]
    missing = [k for k in need if k not in c]
    check("车手字段齐", not missing, "缺 %s" % missing if missing else "")
    print("       P%s %s %s 胎=%s(%s圈) 进站=%s" % (
        c.get("position"), c.get("tla"), c.get("team"),
        c.get("compoundCn"), c.get("tyreLaps"), c.get("pitStops")))

if msgs:
    def cjk(s):
        return any("\u4e00" <= ch <= "\u9fff" for ch in (s or ""))

    # ★ Translator.gloss 的契约是"翻不出来返回 null，调用方显示原文"
    #   （Translator.java:189）。所以不能要求每条都有中文 ——
    #   要验的是：① 有相当一部分翻出来了 ② 没翻出来的回落到英文原文
    glossed = [m for m in msgs if cjk(m.get("gloss"))]
    fellback = [m for m in msgs if not cjk(m.get("gloss")) and m.get("text")]
    check("有消息翻成中文", len(glossed) > 0,
          "%d/%d 条有中文简述" % (len(glossed), len(msgs)))
    check("翻不出的回落到英文原文", len(glossed) + len(fellback) == len(msgs),
          "%d 条显示原文" % len(fellback))
    if glossed:
        print("       中文示例: %s" % glossed[0].get("gloss"))
        print("       原文示例: %s" % glossed[0].get("text"))
    raw_only = [m for m in msgs if not cjk(m.get("gloss"))]
    if raw_only:
        print("       只显示原文的: %s" % raw_only[0].get("text"))

# 旗语栏几何必须是 F1Layout 算的（千分比、首段从 0 开始、总和 1000）
if bar:
    total = sum(x.get("width") or 0 for x in bar)
    check("旗语栏几何来自 F1Layout", abs(total - 1000) < 1.5,
          "%d 段，宽度合计 %.1f‰" % (len(bar), total))
    check("首段 left = 0", abs((bar[0].get("left") or 0)) < 0.01, bar[0].get("left"))
    print("       分段: %s" % ", ".join(
        "%s(%s)" % (x.get("kind"), x.get("count")) for x in bar))
else:
    print("       旗语栏: 当前无旗语（正常，回放刚开始那一段确实没有）")

# 圆环角度必须是 F1Layout.ringStarts 的形状
n = ring.get("count") or 0
if n:
    starts = ring.get("starts") or []
    want = [i * 360.0 / n for i in range(n)]
    same = len(starts) == n and all(abs(starts[i] - want[i]) < 1e-3 for i in range(n))
    check("圆环角度 = F1Layout.ringStarts", same, starts[:4] if starts else "")

print()
print("== 2b. 不变量：不许有被字符串化的 JSON ==")
# ★ 这条是踩坑后补的。Java 侧 Json.Obj.put(k, String) 会给值加引号，
#   于是 new Json.Arr().done() 的结果变成字符串 '[]' 而不是数组。
#   前端 forEach 立刻 TypeError，但**接口依然全 200** —— 极难发现。
#   所以把一个"值"和"字符串化后的自己"混同，必须由机器来拦。
bad = []


def walk(node, path):
    if isinstance(node, dict):
        for k, v in node.items():
            walk(v, path + "." + str(k))
    elif isinstance(node, list):
        for i, v in enumerate(node):
            walk(v, path + "[%d]" % i)
    elif isinstance(node, str):
        t = node.strip()
        # 只认"整串就是一个合法 JSON 容器"的情况；
        # 消息原文里出现 [CORRECTION] 之类不会误伤（它不是合法 JSON）。
        if t[:1] in "[{" and t[-1:] in "]}":
            try:
                json.loads(t)
                bad.append((path, t[:40]))
            except ValueError:
                pass


walk(s, "state")
check("没有字段是字符串化的 JSON", not bad,
      ("发现 %d 处: %s" % (len(bad), bad[:2])) if bad else "干净")

# 明确的类型断言：这些字段前端要直接当数组用
TYPES = [("track.kinds", list), ("track.yellowSectors", list),
         ("track.doubleYellowSectors", list), ("race.topThree", list),
         ("cars", list), ("messages", list), ("bar", list)]
wrong = []
for path, want in TYPES:
    node = s
    for part in path.split("."):
        node = (node or {}).get(part) if isinstance(node, dict) else None
    if node is not None and not isinstance(node, want):
        wrong.append("%s 是 %s" % (path, type(node).__name__))
check("数组字段确实是数组", not wrong, wrong if wrong else "")

print()
print("== 3. 页面 ==")
for path, needle in [("/", "id=\"main\""), ("/board/tyres", "id=\"board\""),
                     ("/app.js", "F1 ="), ("/style.css", ".flag-seg")]:
    st, body = get(path)
    check(path, st == 200 and needle in body,
          "HTTP %s, %d 字节" % (st, len(body)))

st, _ = get("/board/nope")
check("未知看板仍然 200（页面自己报错）", st == 200, st)

print()
print("== 4. SSE 是否真的在推 ==")
t0 = time.time()
got = 0
req = urllib.request.Request(BASE + "/api/events")
with OPENER.open(req, timeout=25) as r:
    buf = ""
    while time.time() - t0 < 12 and got < 2:
        chunk = r.read(1)
        if not chunk:
            break
        buf += chunk.decode("utf-8", "replace")
        if "event: state" in buf:
            got = buf.count("event: state")
check("SSE 收到状态帧", got >= 1, "%d 帧 / %.1fs" % (got, time.time() - t0))

print()
print("== 5. 弹出/收回接口 ==")
st, body = get("/api/boards")
b = json.loads(body)
check("/api/boards 带 popped 字段", all("popped" in x for x in b.get("boards", [])),
      "%d 块" % len(b.get("boards", [])))
check("/api/boards 带 canNative", "canNative" in b)


def post(path):
    req = urllib.request.Request(BASE + path, method="POST")
    try:
        with OPENER.open(req, timeout=20) as r:
            return r.status, r.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8")


st, body = post("/api/popout/nope")
check("未知看板 → 404", st == 404, st)
check("报错也是 JSON（不是 HTML）", '"error"' in body, body[:60])

# ★ 安全不变量：非回环来源不许让这台电脑开窗口。
#   只从本机走局域网地址请求一次就能验证 —— 服务器看到的来源就不是回环了。
#   这条要是坏了，同网段任何人都能让你的电脑弹窗。
try:
    _s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    _s.connect(("8.8.8.8", 80))      # 不发包，只为让系统选出出口网卡
    LAN = _s.getsockname()[0]
    _s.close()
except Exception:
    LAN = None
if LAN:
    try:
        req = urllib.request.Request("http://%s:%d/api/popout/tyres" % (LAN, PORT),
                                     method="POST")
        with OPENER.open(req, timeout=20) as r:
            d = json.loads(r.read().decode("utf-8"))
        check("非回环请求不开窗口（安全不变量）",
              d.get("mode") == "tab" and not d.get("popped"),
              "%s → mode=%s" % (LAN, d.get("mode")))
    except Exception as e:
        check("非回环请求不开窗口（安全不变量）", False, repr(e))
else:
    print("  [--] 非回环检查跳过（枚举不到局域网地址）")

print()
print("全部通过 ✓" if ok else "★ 有失败项")
sys.exit(0 if ok else 1)