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
 *   <li>{@code sql.circuit.breaker.timeout}         — SQL 执行超时次数，按 sql_type [+ mapper_id] 分类统计</li>
 *   <li>{@code sql.circuit.breaker.open}            — 熔断器开启次数（CLOSED → OPEN），按 sql_type [+ mapper_id] 分类统计</li>
 *   <li>{@code sql.circuit.breaker.fast.fail}       — 熔断期间快速失败次数，按 sql_type [+ mapper_id] 分类统计</li>
 *   <li>{@code sql.circuit.breaker.open.count}      — 当前处于 OPEN 状态的熔断器数量（Gauge，无标签）</li>
 * </ul>
 * 是否带 mapper_id 标签由 {@code sql-circuit-breaker.metrics.include-mapper-id} 控制（默认 true）。
 * <p>
 * 构造时预注册所有指标名称，确保在 /actuator/metrics 中首次访问即可见，无需等待事件发生。
 */
public class MicrometerCircuitBreakerMetrics implements SqlCircuitBreakerMetrics {

    private static final String TAG_SQL_TYPE = "sql_type";
    private static final String TAG_MAPPER_ID = "mapper_id";

    private final MeterRegistry meterRegistry;
    private final boolean includeMapperId;

    private static final String[] SQL_TYPES = {"SELECT", "INSERT", "UPDATE", "DELETE"};

    public MicrometerCircuitBreakerMetrics(MeterRegistry meterRegistry,
                                           CircuitBreakerRegistry circuitBreakerRegistry,
                                           boolean includeMapperId) {
        this.meterRegistry = meterRegistry;
        this.includeMapperId = includeMapperId;
        Gauge.builder("sql.circuit.breaker.open.count", circuitBreakerRegistry, CircuitBreakerRegistry::countOpenCircuits)
                .description("当前处于 OPEN 状态的熔断器数量")
                .register(meterRegistry);
        for (String sqlType : SQL_TYPES) {
            Counter.builder("sql.circuit.breaker.intercept.total")
                    .description("拦截器处理的 SQL 总次数（UNKNOWN/FLUSH 已提前放行，不计入）")
                    .tag(TAG_SQL_TYPE, sqlType)
                    .register(meterRegistry);
            // timeout / open / fast.fail：includeMapperId=true 时预注册以空字符串占位的 mapper_id 标签，
            // 与运行期动态注册的真实 Mapper 条目保持一致的标签结构；
            // includeMapperId=false 时仅按 sql_type 聚合，时间序列数从 N × 4 收敛到 4。
            registerCounter("sql.circuit.breaker.timeout", "SQL 执行超时次数（耗时超过配置阈值）", sqlType, "");
            registerCounter("sql.circuit.breaker.open", "熔断器开启次数（CLOSED → OPEN 状态转换）", sqlType, "");
            registerCounter("sql.circuit.breaker.fast.fail", "熔断器快速失败次数（熔断期间请求被拒绝）", sqlType, "");
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
        registerCounter("sql.circuit.breaker.timeout", "SQL 执行超时次数（耗时超过配置阈值）", sqlType, mapperId).increment();
    }

    @Override
    public void recordOpen(String sqlType, String mapperId) {
        registerCounter("sql.circuit.breaker.open", "熔断器开启次数（CLOSED → OPEN 状态转换）", sqlType, mapperId).increment();
    }

    @Override
    public void recordFastFail(String sqlType, String mapperId) {
        registerCounter("sql.circuit.breaker.fast.fail", "熔断器快速失败次数（熔断期间请求被拒绝）", sqlType, mapperId).increment();
    }

    /**
     * 注册或获取 Counter：includeMapperId=true 时附加 mapper_id 标签，false 时只用 sql_type。
     * Micrometer 内部对相同 (name, tags) 做幂等去重，多次调用返回同一 Counter 实例。
     */
    private Counter registerCounter(String name, String description, String sqlType, String mapperId) {
        Counter.Builder b = Counter.builder(name)
                .description(description)
                .tag(TAG_SQL_TYPE, sqlType);
        if (includeMapperId) {
            b.tag(TAG_MAPPER_ID, mapperId);
        }
        return b.register(meterRegistry);
    }
}
