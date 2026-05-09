package io.github.showingdata.starter.framework.circuitbreaker.metrics;

import io.github.showingdata.starter.framework.circuitbreaker.registry.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * @author chenjiang
 * 基于 Micrometer 的指标实现，业务方引入 spring-boot-actuator 后自动激活。
 * <p>
 * 指标名称：
 * <ul>
 *   <li>{@code sql.circuit.breaker.intercept.total} - 拦截器处理的 SQL 总次数（标签：sql_type）</li>
 *   <li>{@code sql.circuit.breaker.timeout}         - SQL 执行超时次数（标签：sql_type、mapper_id）</li>
 *   <li>{@code sql.circuit.breaker.open}            - 熔断器开启次数（标签：sql_type、mapper_id）</li>
 *   <li>{@code sql.circuit.breaker.fast.fail}       - 快速失败次数（标签：sql_type、mapper_id）</li>
 *   <li>{@code sql.circuit.breaker.open.count}      - 当前处于 OPEN 状态的熔断器数量（Gauge，无标签）</li>
 * </ul>
 */
public class MicrometerCircuitBreakerMetrics implements SqlCircuitBreakerMetrics {

    private static final String TAG_SQL_TYPE = "sql_type";
    private static final String TAG_MAPPER_ID = "mapper_id";

    private final MeterRegistry meterRegistry;

    private static final String[] SQL_TYPES = {"SELECT", "INSERT", "UPDATE", "DELETE"};

    public MicrometerCircuitBreakerMetrics(MeterRegistry meterRegistry, CircuitBreakerRegistry circuitBreakerRegistry) {
        this.meterRegistry = meterRegistry;
        Gauge.builder("sql.circuit.breaker.open.count", circuitBreakerRegistry, CircuitBreakerRegistry::countOpenCircuits)
                .description("当前处于 OPEN 状态的熔断器数量")
                .register(meterRegistry);
        // 预注册 intercept.total，使其在首次 SQL 执行前即可在 /actuator/metrics 中可见
        for (String sqlType : SQL_TYPES) {
            Counter.builder("sql.circuit.breaker.intercept.total")
                    .tag(TAG_SQL_TYPE, sqlType)
                    .register(meterRegistry);
        }
    }

    @Override
    public void recordIntercept(String sqlType) {
        Counter.builder("sql.circuit.breaker.intercept.total")
                .tag(TAG_SQL_TYPE, sqlType)
                .register(meterRegistry)
                .increment();
    }

    @Override
    public void recordTimeout(String sqlType, String mapperId) {
        counter("sql.circuit.breaker.timeout", sqlType, mapperId).increment();
    }

    @Override
    public void recordOpen(String sqlType, String mapperId) {
        counter("sql.circuit.breaker.open", sqlType, mapperId).increment();
    }

    @Override
    public void recordFastFail(String sqlType, String mapperId) {
        counter("sql.circuit.breaker.fast.fail", sqlType, mapperId).increment();
    }

    private Counter counter(String name, String sqlType, String mapperId) {
        return Counter.builder(name).tag(TAG_SQL_TYPE, sqlType).tag(TAG_MAPPER_ID, mapperId).register(meterRegistry);
    }
}
