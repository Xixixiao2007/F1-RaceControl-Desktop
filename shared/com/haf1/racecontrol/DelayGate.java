package com.haf1.racecontrol;

import java.util.ArrayDeque;

import org.json.JSONObject;

/**
 * 延时闸门：收到的记录先入队，过了 N 秒才放给 {@link F1Feed}。
 *
 * 用户要它的原因（原话："消息延时，自定义延时秒数以同步直播流"）：
 * 电视/网络直播比官方计时源**晚**若干秒，直接显示实时数据会"剧透"
 * 电视上还没发生的画面。把整个显示滞后 N 秒，两边就对上了。
 *
 * 三条语义（和用户确认过，改之前先想清楚）：
 * <ol>
 *   <li><b>整份快照（type=3）不延迟</b>：接上时立刻显示当前状态，
 *       否则开局黑屏 N 秒。代价是开局那一瞬间是"现在"，之后的增量才滞后。</li>
 *   <li><b>只影响"什么时候看见"，不丢数据</b>：记录一直在队列里，到期就放行。</li>
 *   <li><b>调大 = 画面先停住、N 秒后继续；调小 = 立即放行已到期的</b>。</li>
 * </ol>
 *
 * 线程模型：{@link #offer} 由读取线程调用；{@link #pump} 由自带的心跳线程或
 * 单测显式调用（单测注入 nowMs，所以是确定性的，不用 sleep）。
 * 本类所有方法都 synchronized，{@link F1Feed} 自己也是同步的。
 */
public final class DelayGate {

    /** 到期放行时回调（真正把它喂给 feed）。 */
    public interface Sink {
        void accept(JSONObject rec);
    }

    /** 心跳间隔：决定放行的时间粒度（要小到画面看着顺）。 */
    static final long TICK_MS = 200;

    /** 延时上限（秒）。UI、配置、命令行都用这一个口径。 */
    public static final int MAX_SECONDS = 600;

    private static final class Entry {
        final JSONObject rec;
        /**
         * 到达时刻（毫秒）。★ 存**到达时间**而不是"到点时间"：
         * 到点必须在 pump 时按**当前**延时现算。第一版存的是算好的 dueAt，
         * 结果 setDelayMs(0) 之后队列里那些记录还在等原来的 30 秒 ——
         * "调小立即生效"这条语义就是假的（被自己的单测当场抓住）。
         */
        final long atMs;

        Entry(JSONObject rec, long atMs) {
            this.rec = rec;
            this.atMs = atMs;
        }
    }

    private final Sink sink;
    private final ArrayDeque<Entry> queue = new ArrayDeque<Entry>();
    private long delayMs;
    private volatile boolean stopped;
    private Thread ticker;
    private long offered;
    private long released;

    public DelayGate(long delayMs, Sink sink) {
        this.sink = sink;
        setDelayMs(delayMs);
    }

    /** 设置延时（毫秒）。0 = 关闭；负数当 0；超过上限就截断。 */
    public synchronized void setDelayMs(long ms) {
        if (ms < 0) {
            ms = 0;
        }
        long max = MAX_SECONDS * 1000L;
        delayMs = ms > max ? max : ms;
    }

    public synchronized long delayMs() {
        return delayMs;
    }

    public synchronized int queued() {
        return queue.size();
    }

    public synchronized long offeredCount() {
        return offered;
    }

    public synchronized long releasedCount() {
        return released;
    }

    /**
     * 入队一条记录。延时为 0 时**直接放行**（不绕队列）—— 这样"关掉延时"
     * 就等于改动前的行为，一条路径都不多走。
     */
    public void offer(JSONObject rec, long nowMs) {
        if (rec == null) {
            return;
        }
        boolean immediate;
        synchronized (this) {
            offered++;
            immediate = delayMs <= 0;
            if (!immediate) {
                queue.addLast(new Entry(rec, nowMs));
            } else {
                released++;
            }
        }
        if (immediate) {
            sink.accept(rec);          // 锁外回调：别攥着锁去干 feed 的活
        }
    }

    /**
     * 把到期的依次放行。{@code nowMs} 由调用方给出 —— 单测注入时钟，
     * 所以测试是确定性的（不靠 sleep 碰运气）。
     */
    public void pump(long nowMs) {
        while (true) {
            JSONObject rec;
            synchronized (this) {
                Entry e = queue.peekFirst();
                // ★ 到点时间**现在算**（到达时刻 + 当前延时），这样改延时立刻生效：
                //   调小 -> 已经"等够"的立刻放行；调大 -> 还在队列里的往后推。
                if (e == null || e.atMs + delayMs > nowMs) {
                    return;
                }
                queue.pollFirst();
                released++;
                rec = e.rec;
            }
            sink.accept(rec);          // 同样在锁外
        }
    }

    /**
     * 清空队列并返回丢掉几条。
     *
     * ★ 收到**新的整份快照**时必须清：队列里那些增量是"上一份状态"的增量，
     *   合并进新快照只会污染（重连时最容易出这种错）。
     */
    public synchronized int clear() {
        int n = queue.size();
        queue.clear();
        return n;
    }

    /** 启动心跳线程，每 {@link #TICK_MS} 毫秒泵一次。幂等。 */
    public synchronized void start() {
        if (ticker != null || stopped) {
            return;
        }
        ticker = new Thread(new Runnable() {
            public void run() {
                while (!stopped) {
                    try {
                        Thread.sleep(TICK_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    pump(System.currentTimeMillis());
                }
            }
        }, "f1-delay");
        ticker.setDaemon(true);
        ticker.start();
    }

    /** 停止并清空。之后不再接受 offer 的放行（队列清掉，见 {@link #clear}）。 */
    public synchronized void stop() {
        stopped = true;
        if (ticker != null) {
            ticker.interrupt();
            ticker = null;
        }
        queue.clear();
    }
}
