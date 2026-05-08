package io.github.showingdata.starter.framework.circuitbreaker.config;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * @author chenjiang
 *
 * <p>
 * ThreadLocal 编程式配置对象，用于在运行时动态覆盖当前线程的熔断参数。
 *
 * <p>优先级：ThreadLocal（本类） &gt; 方法注解 &gt; 接口注解 &gt; 全局配置（application.yml）</p>
 *
 * <p>字段均为粗粒度覆盖：对当前线程的所有 SQL 类型统一生效，null 表示不覆盖，继续向下找注解或全局配置。
 * 若需对 SELECT/INSERT/UPDATE/DELETE 各自设置不同参数，请使用 application.yml 全局配置。</p>
 */
@Data
@Accessors(chain = true)
public class SqlCircuitBreakerConfig {

    /**
     * 超时阈值（毫秒），null 表示不覆盖。对当前线程的所有 SQL 类型统一生效。
     */
    private Long timeoutMs;

    /**
     * 熔断持续时长（毫秒），null 表示不覆盖
     */
    private Long circuitOpenMs;

    /**
     * 连续超时触发熔断次数，null 表示不覆盖。对当前线程的所有 SQL 类型统一生效。
     */
    private Integer failureThreshold;

    /**
     * 是否完全禁用熔断检测。
     * true：跳过所有熔断逻辑直接放行，SQL 执行结果不计入失败次数，也不会触发/重置熔断状态。
     * 适用于定时任务补偿、人工数据修复等明知 SQL 会慢但不希望影响熔断状态的场景。
     */
    private Boolean disableCircuitBreaker;
}
