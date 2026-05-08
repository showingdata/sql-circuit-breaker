package io.github.showingdata.starter.framework.circuitbreaker;


/**
 * @author chenjiang
 * @date 2026/5/1 16:23
 * @className SqlCircuitBreakerException
 * @description SQL 熔断器快速失败异常。
 * 当熔断器处于 OPEN 状态时，拦截器不再向 DB 发送 SQL，直接抛出此异常。
 * 业务方可在 catch 块中捕获此异常并进行降级处理（如返回缓存数据、默认值等）。
 * 通过 {@link #getCircuitKey()} 可获取触发熔断的 SQL 指纹 Key，便于日志追踪。
 * <p>
 * 重写 {@link #fillInStackTrace()} 跳过堆栈填充：快速失败场景下高并发创建大量异常对象，
 * 填充堆栈的 CPU 和内存开销显著，而异常消息中已包含 mapper、circuitKey、SQL 指纹等
 * 关键定位信息，拦截器日志也带有节流的完整上下文，堆栈信息无额外诊断价值。
 */
public class SqlCircuitBreakerException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 触发熔断的 circuitKey，格式为 SQL类型:指纹MD5，如 SELECT:a3f2c1d9...
     */
    private final String circuitKey;

    public SqlCircuitBreakerException(String message, String circuitKey) {
        super(message);
        this.circuitKey = circuitKey;
    }

    /**
     * 跳过堆栈填充，避免高并发快速失败场景下频繁 fillInStackTrace 的 CPU 和内存开销。
     */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }

    public String getCircuitKey() {
        return circuitKey;
    }
}
