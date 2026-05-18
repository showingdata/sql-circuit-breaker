package io.github.showingdata.starter.framework.circuitbreaker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

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
 * isOpen() 中到期重置通过 synchronized double-check 防止并发重复重置；
 * lastFastFailLogTime 使用 AtomicLong + CAS 保证日志节流的原子性。
 * </p>
 */
public class CircuitBreakerState {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerState.class);

    public enum State {
        CLOSED, OPEN
    }

    /**
     * 熔断 key（dsKey:sqlType:fingerprintHash），用于日志中标识是哪个熔断器
     */
    private final String circuitKey;
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
     * 上次快速失败日志打印时间，用于日志节流防止高并发下日志风暴
     */
    private final AtomicLong lastFastFailLogTime = new AtomicLong(0);

    /**
     * 全局 OPEN 状态计数器（所有 State 实例共享同一个引用，由 Registry 持有）。
     * 状态切换时在本类内部增减，供 Gauge 指标 O(1) 读取，避免 Prometheus 每次 scrape
     * 都对 4 个 Guava Cache 全量扫描。
     */
    private final AtomicLong openCount;

    public CircuitBreakerState(String circuitKey, AtomicLong openCount) {
        this.circuitKey = circuitKey;
        this.openCount = openCount;
    }

    public String getCircuitKey() {
        return circuitKey;
    }

    public long getOpenTimestamp() {
        return openTimestamp;
    }

    public long getCircuitOpenMs() {
        return circuitOpenMs;
    }

    /**
     * 判断是否需要快速失败：
     * - CLOSED → false（放行）
     * - OPEN 且未到期 → true（快速失败）
     * - OPEN 且到期 → 重置为 CLOSED，放行
     * <p>
     * 线程安全：外层基于 volatile 读取做快速判断；到期重置时通过 synchronized double-check
     * 保证原子性。synchronized 块内同时校验 state == OPEN 和时间差，防止以下竞态：
     * 线程A外层判断到期 → 线程B通过 onTimeout() 刚设了新的 OPEN（新的 openTimestamp/circuitOpenMs）→
     * 线程A进入 synchronized 误将新的 OPEN 重置为 CLOSED。
     */
    public boolean isOpen() {
        if (state == State.CLOSED) {
            return false;
        }
        // OPEN 状态：检查熔断是否到期
        if (System.currentTimeMillis() - openTimestamp > circuitOpenMs) {
            synchronized (this) {
                // double-check：同时校验状态和时间差，防止并发 onTimeout 设置新 OPEN 后被误重置
                if (state == State.OPEN && System.currentTimeMillis() - openTimestamp > circuitOpenMs) {
                    state = State.CLOSED;
                    consecutiveFail = 0;
                    openCount.decrementAndGet();
                    log.info("[SqlCircuitBreaker] 熔断到期，自动重置为 CLOSED | key={}", circuitKey);
                }
            }
            // 无论是否重置成功，重新读取 state：若仍为 OPEN（被 onTimeout 刷新了时间戳），返回 true
            return state == State.OPEN;
        }
        return true;
    }

    /**
     * SQL 执行超时时调用。返回 true 表示本次调用触发了熔断或刷新了 OPEN 窗口。
     * 仅在 CLOSED → OPEN 的真实转换时增加 openCount，避免并发场景下 OPEN → OPEN 的窗口刷新
     * 被重复计数（场景：T1 isOpen()=false 后正要执行 SQL，T2 onTimeout 已转 OPEN；
     * T1 SQL 慢，回头也调 onTimeout 时 state 已是 OPEN，此时不应再 +1）。
     */
    public synchronized boolean onTimeout(int failureThreshold, long circuitOpenMs) {
        consecutiveFail++;
        if (consecutiveFail >= failureThreshold) {
            boolean wasClosed = (state == State.CLOSED);
            state = State.OPEN;
            openTimestamp = System.currentTimeMillis();
            this.circuitOpenMs = circuitOpenMs;
            consecutiveFail = 0;
            if (wasClosed) {
                openCount.incrementAndGet();
            }
            return true;
        }
        return false;
    }

    /**
     * Cache 驱逐时调用：若被驱逐的条目处于 OPEN 状态，减少 openCount 防止计数漏减。
     * 场景：cache-max-size 达到上限时 LRU 驱逐了一个尚未到期的 OPEN 条目，
     * 此时 isOpen() 的自动重置不会被触发，需通过 Cache 的 removalListener 显式通知。
     * <p>
     * 与 isOpen() / onTimeout 共享 synchronized 锁，保证状态切换与计数器更新的原子性。
     */
    public synchronized void onEvicted() {
        if (state == State.OPEN) {
            state = State.CLOSED;
            consecutiveFail = 0;
            openCount.decrementAndGet();
        }
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
     * 无副作用地读取当前状态是否为 OPEN，不触发到期重置逻辑。
     * 仅供 Gauge 指标统计使用，不作为熔断判断依据。
     */
    public boolean isOpenRaw() {
        return state == State.OPEN;
    }

    /**
     * 快速失败日志节流：同一 circuitKey 在 throttleMs 内只应输出一次日志，防止高并发下日志风暴。
     * 使用 CAS 保证原子性，避免多线程同时通过节流检查导致日志重复打印。
     */
    public boolean shouldLogFastFail(long throttleMs) {
        long last = lastFastFailLogTime.get();
        long now = System.currentTimeMillis();
        return now - last >= throttleMs && lastFastFailLogTime.compareAndSet(last, now);
    }

}
