package io.github.showingdata.starter.framework.circuitbreaker.context;

import io.github.showingdata.starter.framework.circuitbreaker.config.SqlCircuitBreakerConfig;

import java.util.Objects;

/**
 * 跨线程上下文传播任务包装器（Runnable 版）。
 * <p>
 * 与 {@link SqlCircuitBreakerTaskWrapper} 同构，适配 {@link Runnable} 接口，
 * 用于 {@link java.util.concurrent.ThreadPoolExecutor#execute(Runnable)} 等只接受 Runnable 的场景。
 *
 * @author chenjiang
 */
public class SqlCircuitBreakerRunnableWrapper implements Runnable {

    private final Runnable delegate;
    private final SqlCircuitBreakerConfig snapshot;

    public SqlCircuitBreakerRunnableWrapper(Runnable delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.snapshot = SqlCircuitBreakerConfig.copyOf(SqlCircuitBreakerContext.get());
    }

    @Override
    public void run() {
        SqlCircuitBreakerContext.clear();
        if (snapshot != null) {
            SqlCircuitBreakerContext.set(snapshot);
        }
        try {
            delegate.run();
        } finally {
            SqlCircuitBreakerContext.clear();
        }
    }
}
