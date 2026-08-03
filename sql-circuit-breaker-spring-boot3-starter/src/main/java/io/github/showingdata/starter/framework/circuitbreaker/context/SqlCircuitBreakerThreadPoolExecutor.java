package io.github.showingdata.starter.framework.circuitbreaker.context;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.Objects;

/**
 * 自动透传 {@link SqlCircuitBreakerContext} 的线程池。
 * <p>
 * 业务方只需将 {@code new ThreadPoolExecutor(...)} 替换为 {@code new SqlCircuitBreakerThreadPoolExecutor(...)}，
 * 其余代码完全不动，提交的任务即自动获得跨线程上下文传播能力：
 * <ul>
 *   <li>提交时（调用线程）：在 {@link #execute(Runnable)} 里包装任务，捕获当前 ThreadLocal 快照。</li>
 *   <li>执行时（工作线程）：包装器还原快照 → 执行原任务 → finally 清理，防标签泄漏。</li>
 * </ul>
 *
 * <p>所有 {@code submit(...)} 变体均经由 {@link java.util.concurrent.AbstractExecutorService} 走 {@link #execute}，
 * 故只重写 {@code execute} 即覆盖 submit(Callable/Runnable) 全部入口，避免重复包装。
 * 已包装的 Runnable 直接放行，不二次包装。
 *
 * <p><b>不覆盖的场景</b>：响应式管道（Reactor/WebFlux）的线程切换不经过 JDK 线程池，
 * 需在 {@code Mono.fromCallable} 内部 set/clear，或用 {@link SqlCircuitBreakerTaskUtils#wrap} 手动包装。
 *
 * @author chenjiang
 */
public class SqlCircuitBreakerThreadPoolExecutor extends ThreadPoolExecutor {

    public SqlCircuitBreakerThreadPoolExecutor(int corePoolSize, int maximumPoolSize,
                                               long keepAliveTime, TimeUnit unit,
                                               BlockingQueue<Runnable> workQueue) {
        super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue);
    }

    public SqlCircuitBreakerThreadPoolExecutor(int corePoolSize, int maximumPoolSize,
                                               long keepAliveTime, TimeUnit unit,
                                               BlockingQueue<Runnable> workQueue, ThreadFactory threadFactory) {
        super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, threadFactory);
    }

    public SqlCircuitBreakerThreadPoolExecutor(int corePoolSize, int maximumPoolSize,
                                               long keepAliveTime, TimeUnit unit,
                                               BlockingQueue<Runnable> workQueue, RejectedExecutionHandler handler) {
        super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, handler);
    }

    public SqlCircuitBreakerThreadPoolExecutor(int corePoolSize, int maximumPoolSize,
                                               long keepAliveTime, TimeUnit unit,
                                               BlockingQueue<Runnable> workQueue,
                                               ThreadFactory threadFactory, RejectedExecutionHandler handler) {
        super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, threadFactory, handler);
    }

    @Override
    public void execute(Runnable command) {
        Objects.requireNonNull(command, "command must not be null");
        // 已包装的 Runnable 直接放行，避免重复包装。
        // 注：submit(Callable) 经由 AbstractExecutorService 包装成 FutureTask 再走 execute，
        // FutureTask 内部的 Callable 不可见，无法在此识别"是否已预包装"——但双重包装幂等无害
        //（同一线程两次快照值相同，两次 set/clear 互不影响），故不特殊处理。
        if (command instanceof SqlCircuitBreakerRunnableWrapper) {
            super.execute(command);
        } else {
            super.execute(new SqlCircuitBreakerRunnableWrapper(command));
        }
    }
}
