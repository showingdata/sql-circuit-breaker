package io.github.showingdata.starter.framework.circuitbreaker.context;

import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * 跨线程上下文传播静态工具，适配已有线程池不便替换的场景。
 * <p>
 * 业务方在提交处手动包装：
 * <pre>{@code
 * // Callable
 * Future<String> f = executor.submit(SqlCircuitBreakerTaskUtils.wrap(() -> {
 *     return mapper.query();
 * }));
 * // Runnable
 * executor.execute(SqlCircuitBreakerTaskUtils.wrap(() -> mapper.update()));
 * }</pre>
 *
 * <p>新项目建议直接使用 {@link SqlCircuitBreakerThreadPoolExecutor}，零侵入无需手动包装。
 *
 * @author chenjiang
 */
public final class SqlCircuitBreakerTaskUtils {

    private SqlCircuitBreakerTaskUtils() {
    }

    /**
     * 包装 Callable，提交时在调用线程捕获 ThreadLocal 快照，执行时还原、结束后清理。
     */
    public static <T> Callable<T> wrap(Callable<T> task) {
        return new SqlCircuitBreakerTaskWrapper<>(Objects.requireNonNull(task, "task must not be null"));
    }

    /**
     * 包装 Runnable，语义同 {@link #wrap(Callable)}。
     */
    public static Runnable wrap(Runnable task) {
        return new SqlCircuitBreakerRunnableWrapper(Objects.requireNonNull(task, "task must not be null"));
    }
}
