package io.github.showingdata.starter.framework.circuitbreaker.metrics;

import io.github.showingdata.starter.framework.circuitbreaker.registry.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * @author chenjiang
 * 基于 Micrometer 的指标实现，业务方引入 spring-boot-actuator 后自动激活。
 * <p>
 * 指标说明：
 * <ul>
 *   <li>{@code sql.circuit.breaker.intercept.total} — 拦截器处理的 SQL 总次数，按 sql_type 分类统计</li>
 *   <li>{@code sql.circuit.breaker.timeout}         — SQL 执行超时次数，按 sql_type + mapper_id 分类统计</li>
 *   <li>{@code sql.circuit.breaker.open}            — 熔断器开启次数（CLOSED → OPEN），按 sql_type + mapper_id 分类统计</li>
 *   <li>{@code sql.circuit.breaker.fast.fail}       — 熔断期间快速失败次数，按 sql_type + mapper_id 分类统计</li>
 *   <li>{@code sql.circuit.breaker.open.count}      — 当前处于 OPEN 状态的熔断器数量（Gauge，无标签）</li>
 * </ul>
 * 构造时预注册所有指标名称，确保在 /actuator/metrics 中首次访问即可见，无需等待事件发生。
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
        for (String sqlType : SQL_TYPES) {
            Counter.builder("sql.circuit.breaker.intercept.total")
                    .description("拦截器处理的 SQL 总次数（UNKNOWN/FLUSH 已提前放行，不计入）")
                    .tag(TAG_SQL_TYPE, sqlType)
                    .register(meterRegistry);
            // timeout / open / fast.fail 含 mapper_id 标签，预注册时以空字符串占位，
            // 保证指标名称在首次访问即可见，同时与运行期动态注册保持一致的标签结构。
            Counter.builder("sql.circuit.breaker.timeout")
                    .description("SQL 执行超时次数（耗时超过配置阈值）")
                    .tag(TAG_SQL_TYPE, sqlType)
                    .tag(TAG_MAPPER_ID, "")
                    .register(meterRegistry);
            Counter.builder("sql.circuit.breaker.open")
                    .description("熔断器开启次数（CLOSED → OPEN 状态转换）")
                    .tag(TAG_SQL_TYPE, sqlType)
                    .tag(TAG_MAPPER_ID, "")
                    .register(meterRegistry);
            Counter.builder("sql.circuit.breaker.fast.fail")
                    .description("熔断器快速失败次数（熔断期间请求被拒绝）")
                    .tag(TAG_SQL_TYPE, sqlType)
                    .tag(TAG_MAPPER_ID, "")
                    .register(meterRegistry);
        }
    }

    @Override
    public void recordIntercept(String sqlType) {
        Counter.builder("sql.circuit.breaker.intercept.total")
                .description("拦截器处理的 SQL 总次数（UNKNOWN/FLUSH 已提前放行，不计入）")
                .tag(TAG_SQL_TYPE, sqlType)
                .register(meterRegistry)
                .increment();
    }

    @Override
    public void recordTimeout(String sqlType, String mapperId) {
        Counter.builder("sql.circuit.breaker.timeout")
                .description("SQL 执行超时次数（耗时超过配置阈值）")
                .tag(TAG_SQL_TYPE, sqlType)
                .tag(TAG_MAPPER_ID, mapperId)
                .register(meterRegistry)
                .increment();
    }

    @Override
    public void recordOpen(String sqlType, String mapperId) {
        Counter.builder("sql.circuit.breaker.open")
                .description("熔断器开启次数（CLOSED → OPEN 状态转换）")
                .tag(TAG_SQL_TYPE, sqlType)
                .tag(TAG_MAPPER_ID, mapperId)
                .register(meterRegistry)
                .increment();
    }

    @Override
    public void recordFastFail(String sqlType, String mapperId) {
        Counter.builder("sql.circuit.breaker.fast.fail")
                .description("熔断器快速失败次数（熔断期间请求被拒绝）")
                .tag(TAG_SQL_TYPE, sqlType)
                .tag(TAG_MAPPER_ID, mapperId)
                .register(meterRegistry)
                .increment();
    }
}
