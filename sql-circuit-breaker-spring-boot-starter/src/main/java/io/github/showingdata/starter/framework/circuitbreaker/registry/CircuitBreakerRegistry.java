package io.github.showingdata.starter.framework.circuitbreaker.registry;

import io.github.showingdata.starter.framework.circuitbreaker.CircuitBreakerState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * @author chenjiang
 * @date 2026/5/1 16:20
 * 熔断状态注册中心，以 circuitKey（SQL类型:指纹MD5）为 key 管理所有 SQL 的熔断状态。
 * <p>
 * 状态存储在 JVM 内存中，多实例部署时各实例独立计数，互不感知（per-instance 语义）。
 * 内置定时任务（每 5 分钟）清理超过 10 分钟无请求的空闲条目，防止内存泄漏。
 * </p>
 */
public class CircuitBreakerRegistry {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerRegistry.class);

    private final ConcurrentHashMap<String, CircuitBreakerState> registry = new ConcurrentHashMap<>();

    /**
     * 获取指定 circuitKey 对应的熔断状态，不存在则初始化为 CLOSED 并注册。
     * 每次调用都会刷新该条目的活跃时间，防止被 evictIdle 误清理。
     */
    public CircuitBreakerState getOrCreate(String circuitKey) {
        CircuitBreakerState state = registry.computeIfAbsent(circuitKey, k -> new CircuitBreakerState(k));
        state.touch();
        return state;
    }

    /**
     * 定时清理长期无活动的条目，防止内存泄漏（无论 CLOSED/OPEN）。
     * 被清理后若请求再次到来，会重新创建 CLOSED 状态，等同于熔断自然消散。
     */
    @Scheduled(fixedDelay = 300000)
    public void evictIdle() {
        long now = System.currentTimeMillis();
        LongAdder count = new LongAdder();
        registry.entrySet().removeIf(e -> {
            // 无论状态如何，只要超过不活跃阈值就清理。
            // OPEN 条目在有请求时会被 getOrCreate 的 touch() 刷新；
            // 若业务侧已彻底不再调用该 SQL，对应条目不应永久驻留内存。
            // 被清理后若请求再次到来，getOrCreate 会重新创建 CLOSED 状态，等同于熔断自然消散。
            boolean remove = now - e.getValue().getLastActiveTime() > 600000 && !e.getValue().isOpen();
            if (remove) count.increment();
            return remove;
        });
        if (count.longValue() > 0) {
            log.debug("[SqlCircuitBreaker] 清理空闲熔断条目 | 数量={}", count.longValue());
        }
    }
}
