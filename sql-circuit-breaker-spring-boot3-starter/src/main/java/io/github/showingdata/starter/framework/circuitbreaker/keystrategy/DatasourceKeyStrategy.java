package io.github.showingdata.starter.framework.circuitbreaker.keystrategy;

/**
 * 数据源粒度策略：dsKey:sqlType。
 * <p>
 * 同一数据源的同一类型 SQL（所有 SELECT / 所有 INSERT 等）共享一个熔断器，
 * DB 级故障时保护最快触发；代价是单条慢 SQL 会影响同库同类型全部 SQL，
 * 适合"DB 健康优先于单 SQL 隔离"的场景。
 * <p>
 * 注意：粒度到 dsKey:sqlType，不跨 SQL 类型共享（SELECT 与 INSERT 仍独立计数），
 * 避免改造 CircuitBreakerRegistry 的四 Cache 结构。
 *
 * @author chenjiang
 */
public class DatasourceKeyStrategy implements CircuitBreakerKeyStrategy {

    @Override
    public String resolve(CircuitBreakerKeyContext ctx) {
        String dsKey = ctx.getDsKey() != null ? ctx.getDsKey() : "default";
        return dsKey + ":" + ctx.getSqlCommandType().name();
    }
}