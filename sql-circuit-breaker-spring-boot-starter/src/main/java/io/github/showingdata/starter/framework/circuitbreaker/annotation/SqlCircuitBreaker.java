package io.github.showingdata.starter.framework.circuitbreaker.annotation;

import java.lang.annotation.*;

/**
 * @author chenjiang
 * @description SQL 熔断器注解
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SqlCircuitBreaker {

    /**
     * SELECT 超时阈值（毫秒），-1 表示继承上层配置
     */
    long selectTimeout() default -1;

    /**
     * INSERT 超时阈值（毫秒），-1 表示继承上层配置
     */
    long insertTimeout() default -1;

    /**
     * UPDATE 超时阈值（毫秒），-1 表示继承上层配置
     */
    long updateTimeout() default -1;

    /**
     * DELETE 超时阈值（毫秒），-1 表示继承上层配置
     */
    long deleteTimeout() default -1;

    /**
     * 熔断持续时长（毫秒），-1 表示继承上层配置
     */
    long circuitOpenMs() default -1;

    /**
     * SELECT 连续超时触发熔断次数，-1 表示继承上层配置。
     * 优先级高于 failureThreshold，与全局 select-failure-threshold 对应。
     */
    int selectFailureThreshold() default -1;

    /**
     * DML（INSERT/UPDATE/DELETE）连续超时触发熔断次数，-1 表示继承上层配置。
     * 优先级高于 failureThreshold，与全局 dml-failure-threshold 对应。
     */
    int dmlFailureThreshold() default -1;

    /**
     * 通用连续超时触发熔断次数，-1 表示继承上层配置。
     * 当 selectFailureThreshold / dmlFailureThreshold 均未设置时作为兜底。
     */
    int failureThreshold() default -1;

    /**
     * 是否禁用熔断（只执行 SQL，不做熔断检测）
     */
    boolean disableCircuitBreaker() default false;
}
