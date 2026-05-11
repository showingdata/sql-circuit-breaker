package io.github.showingdata.starter.framework.circuitbreaker.annotation;

import java.lang.annotation.*;

/**
 * @author chenjiang
 * @description SQL 熔断器注解，可标注在 Mapper 接口（接口级）或具体方法（方法级），方法级优先级更高。
 * <p>
 * 优先级：ThreadLocal &gt; 方法注解 &gt; 接口注解 &gt; 全局配置（application.yml 按 SQL 类型独立配置）。
 * <p>
 * 注解字段为粗粒度覆盖：对该 Mapper/方法下所有 SQL 类型统一生效，无法针对不同 SQL 类型分别设置。
 * 若需对 SELECT/INSERT/UPDATE/DELETE 各自设置不同参数，请使用 application.yml 全局配置（细粒度，按类型独立配置）。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SqlCircuitBreaker {

    /**
     * 超时阈值（毫秒），-1 表示继承上层配置。对标注方法的所有 SQL 类型统一生效。
     */
    long timeoutMs() default -1;

    /**
     * 熔断持续时长（毫秒），-1 表示继承上层配置
     */
    long circuitOpenMs() default -1;

    /**
     * 连续超时触发熔断次数，-1 表示继承上层配置。对标注方法的所有 SQL 类型统一生效。
     */
    int failureThreshold() default -1;

    /**
     * 是否禁用熔断（只执行 SQL，不做熔断检测）
     */
    boolean disableCircuitBreaker() default false;
}
