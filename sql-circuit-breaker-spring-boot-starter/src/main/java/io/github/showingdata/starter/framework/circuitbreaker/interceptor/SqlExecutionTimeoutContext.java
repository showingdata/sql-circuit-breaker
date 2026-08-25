package io.github.showingdata.starter.framework.circuitbreaker.interceptor;

import org.apache.ibatis.mapping.SqlCommandType;

/**
 * 当前 SQL 执行硬超时上下文。
 *
 * <p>由 {@link SqlCircuitBreakerInterceptor} 在 Executor 层解析最终熔断配置后写入，
 * 再由 {@link SqlExecutionTimeoutInterceptor} 在 StatementHandler.prepare 层读取并设置
 * JDBC {@code Statement#setQueryTimeout(int)}。该上下文只在一次 MyBatis 调用栈内有效，
 * 结束后必须恢复，避免线程池复用污染后续 SQL。</p>
 */
final class SqlExecutionTimeoutContext {

    private static final ThreadLocal<Entry> CTX = new ThreadLocal<>();

    private SqlExecutionTimeoutContext() {
    }

    static Entry get() {
        return CTX.get();
    }

    static Entry set(SqlCommandType sqlType, long timeoutMs) {
        Entry previous = CTX.get();
        CTX.set(new Entry(sqlType, timeoutMs));
        return previous;
    }

    static void restore(Entry previous) {
        if (previous == null) {
            CTX.remove();
        } else {
            CTX.set(previous);
        }
    }

    static final class Entry {
        private final SqlCommandType sqlType;
        private final long timeoutMs;

        private Entry(SqlCommandType sqlType, long timeoutMs) {
            this.sqlType = sqlType;
            this.timeoutMs = timeoutMs;
        }

        SqlCommandType getSqlType() {
            return sqlType;
        }

        long getTimeoutMs() {
            return timeoutMs;
        }
    }
}
