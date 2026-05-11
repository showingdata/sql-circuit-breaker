package io.github.showingdata.starter.framework.circuitbreaker.metrics;

/**
 * @author chenjiang
 * SQL 熔断器指标上报接口。
 * 默认实现为空操作（NoOp），业务方引入 Micrometer 后自动切换为真实实现。
 */
public interface SqlCircuitBreakerMetrics {

    /**
     * 拦截器处理的 SQL 总次数（UNKNOWN/FLUSH 已提前放行，不计入）。
     */
    void recordIntercept(String sqlType);

    /**
     * SQL 执行超时（耗时超过阈值）。
     */
    void recordTimeout(String sqlType, String mapperId);

    /**
     * 熔断器首次开启（CLOSED → OPEN）。
     */
    void recordOpen(String sqlType, String mapperId);

    /**
     * 熔断器处于 OPEN 状态，请求快速失败。
     */
    void recordFastFail(String sqlType, String mapperId);
}
