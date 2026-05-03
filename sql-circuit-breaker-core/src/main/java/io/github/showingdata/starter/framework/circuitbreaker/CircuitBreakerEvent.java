package io.github.showingdata.starter.framework.circuitbreaker;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 熔断事件 DTO，用于向消息中心发送告警通知。
 * <p>
 * 仅在熔断首次打开（CIRCUIT_OPEN）时发送，快速失败期间不发消息，避免高并发下产生消息风暴。
 * </p>
 */
@Data
@Accessors(chain = true)
public class CircuitBreakerEvent implements Serializable {

    /**
     * 应用名（spring.application.name）
     */
    private String applicationName;
    /**
     * Mapper 全限定名 + 方法，如 com.example.OrderMapper.queryByUserId
     */
    private String mapperId;
    /**
     * SQL 指纹
     */
    private String sqlFingerprint;
    /**
     * SQL 类型：SELECT / INSERT / UPDATE / DELETE
     */
    private String sqlType;
    /**
     * 实际执行耗时（毫秒）
     */
    private long cost;
    /**
     * 超时阈值（毫秒）
     */
    private long timeoutThreshold;
    /**
     * 熔断持续时长（毫秒）
     */
    private long circuitOpenMs;
    /**
     * 事件发生时间戳
     */
    private long eventTime;
    /**
     * 事件类型：当前仅有 CIRCUIT_OPEN
     */
    private String eventType;
}
