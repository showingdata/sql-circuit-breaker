package io.github.showingdata.starter.framework.circuitbreaker.keystrategy;

/**
 * 指纹粒度策略（默认）：dsKey:sqlType:fingerprintHash。
 * <p>
 * 与历史行为完全一致，key 粒度最细，适合"单条慢 SQL 隔离"场景。
 *
 * @author chenjiang
 */
public class FingerprintKeyStrategy implements CircuitBreakerKeyStrategy {

    @Override
    public String resolve(CircuitBreakerKeyContext ctx) {
        String dsKey = ctx.getDsKey() != null ? ctx.getDsKey() : "default";
        return dsKey + ":" + ctx.getSqlCommandType().name() + ":" + ctx.getFingerprintHash();
    }
}
