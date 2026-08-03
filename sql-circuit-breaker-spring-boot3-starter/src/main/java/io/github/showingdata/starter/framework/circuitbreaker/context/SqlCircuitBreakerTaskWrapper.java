package io.github.showingdata.starter.framework.circuitbreaker.context;

import io.github.showingdata.starter.framework.circuitbreaker.config.SqlCircuitBreakerConfig;

import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * 跨线程上下文传播任务包装器（Callable 版）。
 * <p>
 * 解决 {@link SqlCircuitBreakerContext} 基于 {@link ThreadLocal} 在线程池/@Async 场景下
 * 上下文丢失的问题：提交任务时在主线程"拍照"捕获当前熔断配置快照，
 * 任务在子线程执行前"还原"快照，执行完"清理"防止线程池线程复用导致的标签泄漏。
 *
 * <p>快照为防御性拷贝（{@link SqlCircuitBreakerConfig#copyOf}）：即便提交后主线程继续
 * mutate 同一 config 对象（如再次 setTimeout），子线程拿到的仍是提交时刻的值。
 *
 * <p>snapshot 为 null（提交时主线程无上下文）时跳过 set，但仍 clear，顺带清理池化线程
 * 上可能残留的上一个任务泄漏的标签——本包装器同时也是泄漏兜底。
 *
 * <p>执行期隔离：call() 开头先 clear 一次，建立干净起点（清掉前一个 raw 任务可能残留的标签），
 * 再 set 快照，最后 finally 再 clear。双重 clear 保证本任务执行期间看到的上下文完全来自快照，
 * 不受 worker 线程历史污染，也不向后续任务泄漏。
 *
 * <p>使用方式：
 * <ul>
 *   <li>新项目：将业务线程池替换为
 *   {@link io.github.showingdata.starter.framework.circuitbreaker.context.SqlCircuitBreakerThreadPoolExecutor}，零侵入自动包装。</li>
 *   <li>已有线程池不便替换：提交处手动包装
 *       {@code executor.submit(SqlCircuitBreakerTaskUtils.wrap(task))}。</li>
 * </ul>
 *
 * @author chenjiang
 */
public class SqlCircuitBreakerTaskWrapper<T> implements Callable<T> {

    private final Callable<T> delegate;
    private final SqlCircuitBreakerConfig snapshot;

    public SqlCircuitBreakerTaskWrapper(Callable<T> delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        // 在调用线程（通常是提交线程）捕获快照，防御性拷贝隔离后续 mutate
        this.snapshot = SqlCircuitBreakerConfig.copyOf(SqlCircuitBreakerContext.get());
    }

    @Override
    public T call() throws Exception {
        // 先 clear：清掉 worker 线程上前一个 raw 任务可能残留的标签，建立干净起点
        SqlCircuitBreakerContext.clear();
        if (snapshot != null) {
            SqlCircuitBreakerContext.set(snapshot);
        }
        try {
            return delegate.call();
        } finally {
            // 无论 snapshot 是否为 null 都清理：兜底池化线程上残留的标签
            SqlCircuitBreakerContext.clear();
        }
    }
}
