package io.github.showingdata.starter.framework.circuitbreaker.config;

import lombok.Builder;
import lombok.Getter;

/**
 * @author chenjiang
 * 针对单次 SQL 拦截已解析好的最终配置，由 {@link ConfigResolver} 按优先级合并生成。
 * <p>
 * 优先级：ThreadLocal &gt; 方法注解 &gt; 接口注解 &gt; 全局配置。
 * 所有字段均已确定为具体值，拦截器直接使用，无需再判断优先级。
 * </p>
 */
@Getter
@Builder
public class ResolvedConfig {

    /**
     * 已针对具体 SQL 类型解析好的超时阈值（毫秒）
     */
    private final long timeout;
    /**
     * 熔断持续时长（毫秒）
     */
    private final long circuitOpenMs;
    /**
     * 触发熔断所需的连续超时次数
     */
    private final int failureThreshold;
    /**
     * 是否完全跳过熔断检测，true 时直接放行 SQL
     */
    private final boolean disableCircuitBreaker;
}
