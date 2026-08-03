package io.github.showingdata.starter.framework.circuitbreaker.keystrategy;

/**
 * 熔断 key 生成策略扩展接口。
 * <p>
 * 决定熔断状态机的 key 粒度，影响熔断隔离范围：
 * <ul>
 *   <li>{@link FingerprintKeyStrategy}：dsKey:sqlType:fingerprintHash（默认，最细）</li>
 *   <li>{@link TableKeyStrategy}：dsKey:sqlType:tableName（按表）</li>
 *   <li>{@link DatasourceKeyStrategy}：dsKey:sqlType（按数据源+类型，最粗）</li>
 * </ul>
 * 业务方可声明自己的 Bean 覆盖默认实现，适配自定义粒度。
 *
 * @author chenjiang
 */
public interface CircuitBreakerKeyStrategy {

    /**
     * 根据上下文生成熔断 key。
     *
     * @param ctx 包含 MappedStatement、BoundSql、SqlCommandType、dsKey、fingerprint、fingerprintHash
     * @return 熔断 key，不能为 null
     */
    String resolve(CircuitBreakerKeyContext ctx);
}
