package io.github.showingdata.starter.framework.circuitbreaker.metrics;

/**
 * @author chenjiang
 * 空操作实现，Micrometer 不在 classpath 时作为默认实现，保证 SDK 无侵入运行。
 */
public class NoOpCircuitBreakerMetrics implements SqlCircuitBreakerMetrics {

    @Override
    public void recordIntercept(String sqlType) {
    }

    @Override
    public void recordTimeout(String sqlType, String mapperId) {
    }

    @Override
    public void recordOpen(String sqlType, String mapperId) {
    }

    @Override
    public void recordFastFail(String sqlType, String mapperId) {
    }
}
