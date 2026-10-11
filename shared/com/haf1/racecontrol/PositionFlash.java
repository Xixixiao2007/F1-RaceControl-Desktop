package com.haf1.racecontrol;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * "名次急剧后退"的判定 —— 用来在名次页面上给那一条车手**闪动提醒**。
 *
 * 用户的原话：「名次页面名次急剧后退（5 秒 2 位、10 秒 3 位）加上车手一条的
 * 闪动提醒」。翻成规则就是：
 *
 * <ul>
 *   <li>5 秒内掉了 <b>≥2</b> 位 → 闪</li>
 *   <li>10 秒内掉了 <b>≥3</b> 位 → 闪</li>
 * </ul>
 *
 * 为什么不是"名次一变就闪"：正常比赛里名次每秒都在小幅换位，那样整张表会一直
 * 在闪，等于没有提醒。用户给的这两个数字正好把"进站/被超/打滑"这种真事件
 * 和"两车缠斗换位"分开。
 *
 * 实现要点：
 * <ul>
 *   <li>每个车手记一小段 (时刻, 名次) 历史，只留最近 {@link #HISTORY_MS}；</li>
 *   <li>采样**按秒节流**：界面每秒会调好几次 {@code cars()}，不节流会白记一堆；</li>
 *   <li>触发后持续 {@link #FLASH_MS}，期间再次触发就续期；</li>
 *   <li>名次**前进**（超车）不闪 —— 用户要盯的是"怎么突然掉这么多"。</li>
 * </ul>
 *
 * 纯逻辑 + 注入时钟，所以能离线确定性单测（见 tztest 里的用例）。
 */
public final class PositionFlash {

    /** 触发后闪多久（毫秒）。够看清，又不至于一直闪。 */
    public static final long FLASH_MS = 8000L;

    /** 采样间隔：最快一秒一条。 */
    static final long SAMPLE_MS = 1000L;
    /** 短窗口：5 秒掉 2 位。 */
    public static final long SHORT_MS = 5000L;
    public static final int SHORT_DROP = 2;
    /** 长窗口：10 秒掉 3 位。 */
    public static final long LONG_MS = 10000L;
    public static final int LONG_DROP = 3;
    /** 历史留多久：比长窗口多两秒，够算"10 秒前"的参照。 */
    static final long HISTORY_MS = LONG_MS + 2000L;

    /** 每个车手的历史样本。 */
    private static final class Hist {
        final List<long[]> samples = new ArrayList<long[]>();   // {时刻, 名次}
        long lastSampleAt;
        long flashUntil;
    }

    private final Map<String, Hist> byNumber = new HashMap<String, Hist>();

    private Hist hist(String no) {
        Hist h = byNumber.get(no);
        if (h == null) {
            h = new Hist();
            h.lastSampleAt = Long.MIN_VALUE;
            byNumber.put(no, h);
        }
        return h;
    }

    /**
     * 记一条采样。名次 &lt;= 0（还不知道）会被忽略。
     *
     * 自己按秒节流：同一个车手 1 秒内多次调用只有第一次算数。
     */
    public synchronized void sample(String no, int position, long nowMs) {
        if (no == null || no.length() == 0 || position <= 0) {
            return;
        }
        Hist h = hist(no);
        if (h.lastSampleAt != Long.MIN_VALUE && nowMs - h.lastSampleAt < SAMPLE_MS) {
            return;                       // 节流
        }
        h.lastSampleAt = nowMs;
        h.samples.add(new long[]{nowMs, position});
        // 丢掉过期的（比长窗口还老，留着没用）
        Iterator<long[]> it = h.samples.iterator();
        while (it.hasNext()) {
            if (nowMs - it.next()[0] > HISTORY_MS) {
                it.remove();
            }
        }
        // 触发判定
        int d5 = drop(h, nowMs, SHORT_MS);
        int d10 = drop(h, nowMs, LONG_MS);
        if (d5 >= SHORT_DROP || d10 >= LONG_DROP) {
            h.flashUntil = nowMs + FLASH_MS;
        }
    }

    /** 某个车手当前是否该闪。 */
    public synchronized boolean flashing(String no, long nowMs) {
        Hist h = byNumber.get(no);
        return h != null && nowMs < h.flashUntil;
    }

    /** 某个窗口内掉了多少位（没掉或没有历史返回 0）。 */
    public synchronized int droppedIn(String no, long windowMs, long nowMs) {
        Hist h = byNumber.get(no);
        return h == null ? 0 : drop(h, nowMs, windowMs);
    }

    /** 清空（换场、回放重开时用）。 */
    public synchronized void clear() {
        byNumber.clear();
    }

    /**
     * 相对"窗口起点"掉了多少位。
     *
     * 参照取窗口内**最早**那个样本（最接近 now-window 的已知名次）；
     * 名次数字变大 = 往后掉。
     */
    private static int drop(Hist h, long nowMs, long windowMs) {
        long from = nowMs - windowMs;
        long refPos = -1;
        long curPos = -1;
        for (int i = 0; i < h.samples.size(); i++) {
            long[] s = h.samples.get(i);
            if (s[0] >= from) {
                refPos = s[1];
                break;
            }
        }
        if (h.samples.size() > 0) {
            curPos = h.samples.get(h.samples.size() - 1)[1];
        }
        if (refPos <= 0 || curPos <= 0) {
            return 0;
        }
        long d = curPos - refPos;
        return d > 0 ? (int) d : 0;
    }
}
