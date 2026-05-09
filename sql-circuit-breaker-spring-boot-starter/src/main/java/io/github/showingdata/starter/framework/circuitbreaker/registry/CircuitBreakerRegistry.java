package io.github.showingdata.starter.framework.circuitbreaker.registry;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import io.github.showingdata.starter.framework.circuitbreaker.CircuitBreakerState;
import io.github.showingdata.starter.framework.circuitbreaker.config.SqlCircuitBreakerProperties;
import org.apache.ibatis.mapping.SqlCommandType;

import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * @author chenjiang
 * 熔断状态注册中心，按 SQL 类型维护四个独立 Guava Cache。
 * <p>
 * Guava Cache 内置 LRU 驱逐（maximumSize）和访问过期（expireAfterAccess），
 * 无需手动定时清理任务。各类型缓存容量和过期时间均独立配置。
 * 状态存储在 JVM 内存中，多实例部署时各实例独立计数（per-instance 语义）。
 * </p>
 */
public class CircuitBreakerRegistry {

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
        return CacheBuilder.newBuilder()
                .maximumSize(config.getCacheMaxSize())
                .expireAfterAccess(config.getCacheExpireAfterAccessMinutes(), TimeUnit.MINUTES)
                .build();
    }

    /**
     * 获取指定 circuitKey 和 SQL 类型对应的熔断状态，不存在则初始化为 CLOSED 并放入对应类型的缓存。
     * Guava Cache 的 expireAfterAccess 在每次 get 时自动刷新访问时间，无需手动 touch()。
     */
    public CircuitBreakerState getOrCreate(String circuitKey, SqlCommandType type) {
        try {
            return cacheFor(type).get(circuitKey, () -> new CircuitBreakerState(circuitKey));
        } catch (ExecutionException e) {
            throw new IllegalStateException("[SqlCircuitBreaker] 无法创建 CircuitBreakerState, key=" + circuitKey, e);
        }
    }

    /**
     * 统计当前处于 OPEN 状态的熔断器数量，供 Gauge 指标使用。
     * 使用 {@link CircuitBreakerState#isOpenRaw()} 纯读状态，不触发到期重置。
     */
    public long countOpenCircuits() {
        long count = 0;
        for (Cache<String, CircuitBreakerState> cache : Arrays.asList(selectCache, insertCache, updateCache, deleteCache)) {
            for (CircuitBreakerState s : cache.asMap().values()) {
                if (s.isOpenRaw()) count++;
            }
        }
        return count;
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
