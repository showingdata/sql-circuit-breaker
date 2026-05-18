package io.github.showingdata.starter.framework.circuitbreaker.registry;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;
import io.github.showingdata.starter.framework.circuitbreaker.CircuitBreakerState;
import io.github.showingdata.starter.framework.circuitbreaker.config.SqlCircuitBreakerProperties;
import org.apache.ibatis.mapping.SqlCommandType;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author chenjiang
 * 熔断状态注册中心，按 SQL 类型维护四个独立 Guava Cache。
 * <p>
 * Guava Cache 内置 LRU 驱逐（maximumSize）和访问过期（expireAfterAccess），
 * 无需手动定时清理任务。各类型缓存容量和过期时间均独立配置。
 * 状态存储在 JVM 内存中，多实例部署时各实例独立计数（per-instance 语义）。
 * </p>
 * <p>
 * 维护全局 {@link #openCount} 计数器供 Gauge 指标 O(1) 读取：
 * <ul>
 *   <li>CLOSED → OPEN（{@link CircuitBreakerState#onTimeout}）：+1</li>
 *   <li>OPEN → CLOSED 到期自动重置（{@link CircuitBreakerState#isOpen}）：-1</li>
 *   <li>OPEN 条目被 Cache 驱逐（LRU/过期，通过 removalListener 回调 {@link CircuitBreakerState#onEvicted}）：-1</li>
 * </ul>
 * 上述三条路径覆盖了所有"状态变化导致 OPEN 计数变化"的场景。
 * </p>
 */
public class CircuitBreakerRegistry {

    /**
     * 当前处于 OPEN 状态的熔断器数量，所有 CircuitBreakerState 实例共享此引用。
     * 取代原来 O(N) 全扫描四个 Cache 的实现，scrape 频繁 + cache-max-size 大时性能差异显著。
     */
    private final AtomicLong openCount = new AtomicLong(0);

    private final Cache<String, CircuitBreakerState> selectCache;
    private final Cache<String, CircuitBreakerState> insertCache;
    private final Cache<String, CircuitBreakerState> updateCache;
    private final Cache<String, CircuitBreakerState> deleteCache;

    public CircuitBreakerRegistry(SqlCircuitBreakerProperties props) {
        this.selectCache = buildCache(props.getSelect());
        this.insertCache = buildCache(props.getInsert());
        this.updateCache = buildCache(props.getUpdate());
        this.deleteCache = buildCache(props.getDelete());
    }

    private Cache<String, CircuitBreakerState> buildCache(SqlCircuitBreakerProperties.SqlTypeConfig config) {
        RemovalListener<String, CircuitBreakerState> listener = notification -> {
            CircuitBreakerState evicted = notification.getValue();
            if (evicted != null) {
                // 驱逐时若该条目仍为 OPEN，需要同步减少计数；onEvicted 内部加锁，幂等安全
                evicted.onEvicted();
            }
        };
        return CacheBuilder.newBuilder()
                .maximumSize(config.getCacheMaxSize())
                .expireAfterAccess(config.getCacheExpireAfterAccessMinutes(), TimeUnit.MINUTES)
                .removalListener(listener)
                .build();
    }

    /**
     * 获取指定 circuitKey 和 SQL 类型对应的熔断状态，不存在则初始化为 CLOSED 并放入对应类型的缓存。
     * Guava Cache 的 expireAfterAccess 在每次 get 时自动刷新访问时间，无需手动 touch()。
     */
    public CircuitBreakerState getOrCreate(String circuitKey, SqlCommandType type) {
        try {
            return cacheFor(type).get(circuitKey, () -> new CircuitBreakerState(circuitKey, openCount));
        } catch (ExecutionException e) {
            throw new IllegalStateException("[SqlCircuitBreaker] 无法创建 CircuitBreakerState, key=" + circuitKey, e);
        }
    }

    /**
     * 当前处于 OPEN 状态的熔断器数量，供 Gauge 指标使用。O(1) 读取。
     */
    public long countOpenCircuits() {
        return openCount.get();
    }

    private Cache<String, CircuitBreakerState> cacheFor(SqlCommandType type) {
        switch (type) {
            case SELECT: return selectCache;
            case INSERT: return insertCache;
            case UPDATE: return updateCache;
            case DELETE: return deleteCache;
            default: throw new IllegalArgumentException("[SqlCircuitBreaker] 不支持的 SQL 类型: " + type);
        }
    }
}
