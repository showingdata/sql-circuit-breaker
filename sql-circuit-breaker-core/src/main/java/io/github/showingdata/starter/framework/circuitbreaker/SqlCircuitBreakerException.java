package io.github.showingdata.starter.framework.circuitbreaker;


/**
 * @author chenjiang
 * @date 2026/5/1 16:23
 * @className SqlCircuitBreakerException
 * @description SQL 熔断器快速失败异常。
 * 当熔断器处于 OPEN 状态时，拦截器不再向 DB 发送 SQL，直接抛出此异常。
 * 业务方可在 catch 块中捕获此异常并进行降级处理（如返回缓存数据、默认值等）。
 * 通过 {@link #getCircuitKey()} 可获取触发熔断的 SQL 指纹 Key，便于日志追踪。
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

    public String getCircuitKey() {
        return circuitKey;
    }
}
