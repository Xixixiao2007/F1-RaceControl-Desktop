package com.haf1.racecontrol;

/**
 * 数据源。目前两个实现：
 *
 * <ul>
 *   <li>{@link F1Client} —— 连官方公开流（真数据）</li>
 *   <li>{@link ReplayClient} —— 放官方归档压成的回放包（假数据，但字节格式一样）</li>
 * </ul>
 *
 * 主界面只认这个接口，所以"换数据源"这件事在界面代码里只有一个分支点。
 */
public interface FeedSource {

    /** 累积状态。界面从这里读所有面板。 */
    F1Feed feed();

    /** 最后一次错误，没有就是空串。 */
    String lastError();

    /** 从外部线程调用以中止。 */
    void stop();

    /** 阻塞运行，直到结束或被 {@link #stop()}。要放到独立线程里。 */
    void runForever();
}