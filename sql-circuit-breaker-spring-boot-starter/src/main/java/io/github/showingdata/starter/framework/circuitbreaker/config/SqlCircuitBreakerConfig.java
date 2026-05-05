package io.github.showingdata.starter.framework.circuitbreaker.config;

import lombok.Data;
import lombok.experimental.Accessors;
import org.apache.ibatis.mapping.SqlCommandType;

/**
 * @author chenjiang
 *
 * <p>
 * ThreadLocal 编程式配置对象，用于在运行时动态覆盖当前线程的熔断参数。
 *
 * <p>优先级：ThreadLocal（本类） &gt; 方法注解 &gt; 接口注解 &gt; 全局配置（application.yml）</p>
 *
 * </p>
 *
 * <p>注意：所有字段均为可选，null 表示"不覆盖，继续向下找注解或全局配置"。</p>
 */
@Data
@Accessors(chain = true)
public class SqlCircuitBreakerConfig {

    /**
     * SELECT 超时阈值（毫秒），null 表示不覆盖
     */
    private Long selectTimeoutMs;

    /**
     * INSERT 超时阈值（毫秒），null 表示不覆盖
     */
    private Long insertTimeoutMs;

    /**
     * UPDATE 超时阈值（毫秒），null 表示不覆盖
     */
    private Long updateTimeoutMs;

    /**
     * DELETE 超时阈值（毫秒），null 表示不覆盖
     */
    private Long deleteTimeoutMs;

    /**
     * 熔断持续时长（毫秒），null 表示不覆盖
     */
    private Long circuitOpenMs;

    /**
     * SELECT 连续超时触发熔断次数，null 表示不覆盖。优先级高于 failureThreshold。
     */
    private Integer selectFailureThreshold;

    /**
     * DML（INSERT/UPDATE/DELETE）连续超时触发熔断次数，null 表示不覆盖。优先级高于 failureThreshold。
     */
    private Integer dmlFailureThreshold;

    /**
     * 通用连续超时触发熔断次数，null 表示不覆盖。
     * selectFailureThreshold / dmlFailureThreshold 均为 null 时作为兜底。
     */
    private Integer failureThreshold;

    /**
     * 是否完全禁用熔断检测。
     * true：跳过所有熔断逻辑直接放行，SQL 执行结果不计入失败次数，也不会触发/重置熔断状态。
     * 适用于定时任务补偿、人工数据修复等明知 SQL 会慢但不希望影响熔断状态的场景。
     */
    private Boolean disableCircuitBreaker;

    /**
     * 根据 SQL 类型返回对应的超时阈值，null 表示当前线程未设置该类型的超时，交由下层配置兜底
     */
    public Long getTimeout(SqlCommandType type) {
        switch (type) {
            case SELECT:
                return selectTimeoutMs;
            case INSERT:
                return insertTimeoutMs;
            case UPDATE:
                return updateTimeoutMs;
            case DELETE:
                return deleteTimeoutMs;
            default:
                return null;
        }
    }
}
