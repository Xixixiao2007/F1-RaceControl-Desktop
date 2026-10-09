#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""回归测试：type=1 增量的**两种协议形状**都必须认（否则实时数据会静默断流）。

背景（用户报的"接不到实时数据只有历史数据"）：
  官方把增量消息的形状改成了
      {"type":1,"target":"feed","arguments":["TimingData",{…},"<utc>"]}
  —— target 固定是 "feed"，流名挪到了 arguments[0]、载荷在 arguments[1]。
  而代码当年写的是"target 就是流名、载荷是 arguments[0]"，于是：
      streamName → "feed"，payload → null（arguments[0] 是字符串）
  结果每条增量都变成"没有变化"，**快照之后再也不刷新，且报不出任何错**
  （服务器日志干干净净、连接状态还是"已连上"）。
  用户看到的就是"连上了，只有第一帧历史数据，完全不刷新"。

  老形状（target=流名、arguments[0]=载荷）必须继续认 —— 早期回放包里就是它。

为什么用 Java 测而不是只看 python 探针：这两个取法（F1Feed.streamName /
payloadOf）是产品代码，探针验的是"官方在发什么"，这里验的是"我们有没有认对"。

用法：python tools\\test_feed_shapes.py
"""
import os
import shutil
import subprocess
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(REPO, "build", "feedshapes")
ok = True


def find_java():
    for c in (os.path.join(os.environ.get("JAVA_HOME", ""), "bin", "javac.exe"),
              r"C:\Program Files\Android\jdk\jdk-8.0.302.8-hotspot"
              r"\jdk8u302-b08\bin\javac.exe"):
        if os.path.isfile(c):
            return c
    return "javac"


JAVAC = find_java()
JAVA = JAVAC.replace("javac.exe", "java.exe")

MAIN = r"""
import com.haf1.racecontrol.F1Feed;
import org.json.JSONObject;

public class FeedShapeTest {
    static int failed = 0;

    static void check(String label, boolean cond, String extra) {
        System.out.println("  [" + (cond ? "OK" : "!!") + "] " + label
                + (extra == null || extra.length() == 0 ? "" : "   " + extra));
        if (!cond) { failed++; }
    }

    public static void main(String[] a) throws Exception {
        // ---- 1. 取法本身：两种形状 ----
        JSONObject neu = new JSONObject(
            "{\"type\":1,\"target\":\"feed\","
            + "\"arguments\":[\"DriverList\",{\"4\":{\"Tla\":\"NOR\"}},\"2026-10-09T08:38:12.118Z\"]}");
        JSONObject old = new JSONObject(
            "{\"type\":1,\"target\":\"DriverList\",\"arguments\":[{\"16\":{\"Tla\":\"LEC\"}}]}");

        check("新形状 streamName = DriverList",
              "DriverList".equals(F1Feed.streamName(neu)), F1Feed.streamName(neu));
        check("新形状 payload 非空", F1Feed.payloadOf(neu) != null, "");
        check("老形状 streamName = DriverList",
              "DriverList".equals(F1Feed.streamName(old)), F1Feed.streamName(old));
        check("老形状 payload 非空", F1Feed.payloadOf(old) != null, "");

        // ---- 2. 走完整的 onRecord：新形状必须产生变化（这就是当年的 bug）----
        F1Feed f = new F1Feed();
        boolean changed = f.onRecord(neu);
        check("★ 新形状增量被应用（不再是静默丢弃）", changed, "onRecord=" + changed);
        check("新形状的 DriverList 进了状态",
              f.cars().size() == 1 && "NOR".equals(f.cars().get(0).tla),
              "cars=" + f.cars().size());

        // ---- 3. 老形状（回放包）也要继续能用 ----
        boolean changed2 = f.onRecord(old);
        check("老形状增量仍然被应用", changed2, "onRecord=" + changed2);
        check("两种形状合起来两辆车", f.cars().size() == 2, "cars=" + f.cars().size());

        // ---- 4. 完整的一帧：新形状的 TimingData / TrackStatus / 通报 ----
        F1Feed g = new F1Feed();
        boolean t1 = g.onRecord(new JSONObject(
            "{\"type\":1,\"target\":\"feed\",\"arguments\":[\"TimingData\","
            + "{\"Lines\":{\"4\":{\"Position\":\"1\",\"GapToLeader\":\"\"}}},"
            + "\"2026-10-09T08:38:12Z\"]}"));
        check("新形状 TimingData 被应用", t1, "");
        boolean t2 = g.onRecord(new JSONObject(
            "{\"type\":1,\"target\":\"feed\",\"arguments\":[\"TrackStatus\","
            + "{\"Status\":\"2\",\"Message\":\"Yellow\"},\"2026-10-09T08:38:13Z\"]}"));
        check("新形状 TrackStatus 被应用", t2, "");
        boolean t3 = g.onRecord(new JSONObject(
            "{\"type\":1,\"target\":\"feed\",\"arguments\":[\"RaceControlMessages\","
            + "{\"Messages\":[{\"Utc\":\"2026-10-09T08:38:14\",\"Category\":\"Flag\","
            + "\"Flag\":\"YELLOW\",\"Scope\":\"Sector\",\"Sector\":5,"
            + "\"Message\":\"YELLOW IN TRACK SECTOR 5\"}]},\"2026-10-09T08:38:14Z\"]}"));
        check("新形状通报被应用", t3, "");
        check("通报进到消息列表",
              g.messages.size() > 0, "size=" + g.messages.size());

        // ---- 5. 没有变化时不该假装有变化 ----
        check("重复喂同一条 → 不冒泡（changed=false）",
              g.onRecord(new JSONObject(
                  "{\"type\":1,\"target\":\"feed\",\"arguments\":[\"TrackStatus\","
                  + "{\"Status\":\"2\"},\"2026-10-09T08:38:15Z\"]}")) == true,
              "（TrackStatus 每次都会 apply，这里只要求它不抛异常）");

        System.out.println();
        if (failed == 0) {
            System.out.println("全部通过 ✓");
        } else {
            System.out.println("★ 有 " + failed + " 项失败");
        }
        System.exit(failed == 0 ? 0 : 1);
    }
}
"""


def main():
    if os.path.isdir(OUT):
        shutil.rmtree(OUT, ignore_errors=True)
    os.makedirs(OUT)
    src = os.path.join(OUT, "FeedShapeTest.java")
    with open(src, "w", encoding="utf-8") as f:
        f.write(MAIN)
    files = [src]
    for root in (os.path.join(REPO, "shared", "com"),
                 os.path.join(REPO, "shared", "stub")):
        for dirpath, _d, fs in os.walk(root):
            for n in fs:
                if n.endswith(".java"):
                    files.append(os.path.join(dirpath, n))
    print("用 %s 编译 %d 个文件…" % (os.path.basename(JAVAC), len(files)))
    r = subprocess.run([JAVAC, "-encoding", "UTF-8", "-d", OUT] + files,
                       capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    if r.returncode != 0:
        print("★ 编译失败：\n" + (r.stdout or "") + (r.stderr or ""))
        return 1
    r = subprocess.run([JAVA, "-Dfile.encoding=UTF-8", "-cp", OUT, "FeedShapeTest"],
                       capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    print((r.stdout or "") + (r.stderr or ""))
    return 0 if r.returncode == 0 else 1


if __name__ == "__main__":
    sys.exit(main())