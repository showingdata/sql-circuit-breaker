package io.github.showingdata.starter.framework.circuitbreaker.keystrategy;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;

/**
 * 熔断 key 生成上下文，封装策略所需的所有信号。
 * <p>
 * 所有字段由拦截器在调用前算好（指纹/哈希本就用于日志），策略本身零额外计算成本。
 *
 * @author chenjiang
 */
@Getter
@RequiredArgsConstructor
public final class CircuitBreakerKeyContext {

    private final MappedStatement mappedStatement;
    private final BoundSql boundSql;
    private final SqlCommandType sqlCommandType;

    /** 数据源标识，可能为 null，策略自行处理为 "default" */
    private final String dsKey;

    /** 已归一化的 SQL 指纹（用于日志展示或自定义策略参考） */
    private final String fingerprint;

    /** 指纹的 MD5，fingerprint 策略直接用作 key 第三段，table 策略解析失败时回退到此 */
    private final String fingerprintHash;
}
