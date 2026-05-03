package io.github.showingdata.starter.framework.circuitbreaker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author chenjiang
 * @date 2026/5/1 16:20
 * @description SQL超时熔断器设计思想:来源于分布式微服务架构中的熔断器设计思想 结合本SDK需求去掉了HALF_OPEN状态
 *
 * <a href="https://juejin.cn/post/7313797195475025935"/>
 * <a href="https://www.cnblogs.com/Zachary-Fan/p/circuitbreaker.html"/>
 *
 * <p>
 * 单个 SQL 指纹的熔断状态机，持有 CLOSED / OPEN 两态及相关计数。
 *
 * <p>
 * 状态转换：
 * <pre>
 *   CLOSED ──(连续超时 >= failureThreshold)──→ OPEN
 *     ↑                                          │
 *     └──────── circuitOpenMs 到期自动重置 ───────┘
 * </pre>
 * <p>
 * 熔断期间（OPEN）拒绝所有请求；circuitOpenMs 到期后自动重置为 CLOSED，
 * 若 DB 仍慢则连续超时再次触发熔断。
 *
 * <p>
 * 线程安全：状态字段均为 volatile；onTimeout / onSuccess 加 synchronized 保证原子性；
 * isOpen() 中到期重置通过 synchronized double-check 防止并发重复重置。
 * </p>
 */
public class CircuitBreakerState {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerState.class);

    public enum State {
        CLOSED, OPEN
    }

    /**
     * SQL 指纹，用于日志中标识是哪条 SQL 的熔断状态
     */
    private final String sqlFingerprint;
    /**
     * 当前熔断状态
     */
    private volatile State state = State.CLOSED;
    /**
     * 进入 OPEN 状态的时间戳，用于判断熔断是否到期
     */
    private volatile long openTimestamp;
    /**
     * 本次熔断的持续时长（毫秒）
     */
    private volatile long circuitOpenMs;
    /**
     * 连续超时次数，达到 failureThreshold 则触发熔断；成功后重置为 0
     */
    private volatile int consecutiveFail;
    /**
     * 最近一次活跃时间，供 CircuitBreakerRegistry.evictIdle() 判断是否回收
     */
    private volatile long lastActiveTime;
    /**
     * 上次快速失败日志打印时间，用于日志节流防止高并发下日志风暴
     */
    private volatile long lastFastFailLogTime;

    public CircuitBreakerState(String sqlFingerprint) {
        this.sqlFingerprint = sqlFingerprint;
        this.lastActiveTime = System.currentTimeMillis();
    }

    public String getSqlFingerprint() {
        return sqlFingerprint;
    }

    public long getOpenTimestamp() {
        return openTimestamp;
    }

    public long getCircuitOpenMs() {
        return circuitOpenMs;
    }

    public long getLastActiveTime() {
        return lastActiveTime;
    }

    /**
     * 判断是否需要快速失败：
     * - CLOSED → false（放行）
     * - OPEN 且未到期 → true（快速失败）
     * - OPEN 且到期 → 重置为 CLOSED，放行
     */
    public boolean isOpen() {
        if (state == State.CLOSED) {
            return false;
        }
        // OPEN 状态：检查熔断是否到期
        if (System.currentTimeMillis() - openTimestamp > circuitOpenMs) {
            synchronized (this) {
                // double-check：防止多线程同时到期时重复重置
                if (state == State.OPEN) {
                    state = State.CLOSED;
                    consecutiveFail = 0;
                    log.info("[SqlCircuitBreaker] 熔断到期，自动重置为 CLOSED | key={}", sqlFingerprint);
                }
            }
            return false;
        }
        return true;
    }

    /**
     * SQL 执行超时时调用。返回 true 表示本次调用触发了熔断（状态由 CLOSED → OPEN）。
     */
    public synchronized boolean onTimeout(int failureThreshold, long circuitOpenMs) {
        consecutiveFail++;
        if (consecutiveFail >= failureThreshold) {
            state = State.OPEN;
            openTimestamp = System.currentTimeMillis();
            this.circuitOpenMs = circuitOpenMs;
            consecutiveFail = 0;
            return true;
        }
        return false;
    }

    /**
     * SQL 正常执行成功（CLOSED 状态下 cost 未超时）。
     * 重置连续失败计数，保证 failureThreshold 语义是"连续"超时而非"累计"超时。
     */
    public synchronized void onSuccess() {
        if (state == State.CLOSED && consecutiveFail > 0) {
            consecutiveFail = 0;
        }
    }

    public boolean isClosed() {
        return state == State.CLOSED;
    }

    /**
     * 快速失败日志节流：同一 circuitKey 在 throttleMs 内只应输出一次日志，防止高并发下日志风暴。
     */
    public boolean shouldLogFastFail(long throttleMs) {
        long now = System.currentTimeMillis();
        if (now - lastFastFailLogTime >= throttleMs) {
            lastFastFailLogTime = now;
            return true;
        }
        return false;
    }

    /**
     * 刷新最近活跃时间，供 evictIdle 判断是否清理
     */
    public void touch() {
        lastActiveTime = System.currentTimeMillis();
    }


}
