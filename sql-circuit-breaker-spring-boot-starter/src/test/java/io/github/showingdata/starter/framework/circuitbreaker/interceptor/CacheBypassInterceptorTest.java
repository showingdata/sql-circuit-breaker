package io.github.showingdata.starter.framework.circuitbreaker.interceptor;

import io.github.showingdata.starter.framework.circuitbreaker.SqlCircuitBreakerException;
import io.github.showingdata.starter.framework.circuitbreaker.config.ConfigResolver;
import io.github.showingdata.starter.framework.circuitbreaker.config.SqlCircuitBreakerProperties;
import io.github.showingdata.starter.framework.circuitbreaker.datasource.DefaultDataSourceKeyResolver;
import io.github.showingdata.starter.framework.circuitbreaker.message.NoOpMessageCenterClient;
import io.github.showingdata.starter.framework.circuitbreaker.metrics.NoOpCircuitBreakerMetrics;
import io.github.showingdata.starter.framework.circuitbreaker.registry.CircuitBreakerRegistry;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「混合层级」拦截器对 MyBatis 二级缓存命中的处理验证。
 *
 * <p>核心命题：缓存命中不经过 {@code StatementHandler} 层，因此既不计入超时、也不会触发
 * {@code onSuccess()} 清零连续超时计数；只有真实落库的执行才会影响熔断状态。
 * 这从根本上修复了「缓存命中被当成一次快速成功、把连续超时计数清零，导致慢 SQL 永远无法触发熔断」的缺陷。
 *
 * <p>构造手法：{@code SELECT SLOWFN(?)} 对所有入参是同一 SQL 指纹（同一个熔断器），
 * 但不同入参 = 不同的二级缓存 Key。于是用「同参」制造缓存命中、「异参」制造真实慢查询，
 * 即可在同一个熔断器上交替出现「真实慢执行」与「缓存命中」。
 */
class CacheBypassInterceptorTest {

    /** 同名内存库 + DB_CLOSE_DELAY=-1：整个 JVM 生命周期保持存活，ALIAS 一次创建处处可用。 */
    private static final String URL = "jdbc:h2:mem:cb_cache_test;DB_CLOSE_DELAY=-1";

    /** 超时阈值（ms）：慢查询 sleep 远大于它、快查询(0ms)远小于它，留足余量避免机器抖动误判。 */
    private static final long TIMEOUT_MS = 150L;
    private static final int SLOW_MS = 500;

    @BeforeAll
    static void setUp() throws Exception {
        // 注册 H2 自定义函数 SLOWFN
        try (Connection c = DriverManager.getConnection(URL, "sa", "");
             Statement st = c.createStatement()) {
            st.execute("CREATE ALIAS IF NOT EXISTS SLOWFN FOR \""
                    + SlowFunctions.class.getName() + ".slowFn\"");
        }
        // 预热 MyBatis/H2 相关类加载，降低首个查询冷启动对计时的干扰
        Harness warm = newHarness(2);
        warm.op(0);
        warm.op(0);
    }

    @Test
    @DisplayName("二级缓存命中不应清零连续超时计数：两次真实慢查询后应触发熔断")
    void cacheHitDoesNotResetConsecutiveTimeoutCount() {
        Harness h = newHarness(2);
        h.op(SLOW_MS);          // ① 未命中 → 真实慢查询 → 连续超时=1
        h.op(SLOW_MS);          // ② 同参 → 二级缓存命中 → 秒回，绝不能清零计数
        h.op(SLOW_MS + 1);      // ③ 异参(同指纹) → 未命中 → 真实慢查询 → 连续超时=2 → 触发熔断

        assertEquals(1, h.openCircuits(),
                "若缓存命中被错误计为成功而清零计数，此处熔断器仍为关闭——这正是修复前的 bug");
        assertTrue(h.opThrowsCircuitOpen(SLOW_MS),
                "熔断 OPEN 后，即便是会命中缓存的请求也应在 Executor 层(申请连接前)快速失败");
    }

    @Test
    @DisplayName("回归：连续两次真实慢查询应触发熔断")
    void consecutiveRealTimeoutsTrip() {
        Harness h = newHarness(2);
        h.op(SLOW_MS);          // 未命中 → 慢 → 1
        h.op(SLOW_MS + 1);      // 未命中 → 慢 → 2 → 熔断
        assertEquals(1, h.openCircuits());
    }

    @Test
    @DisplayName("回归：真实快查询应重置连续计数，未达阈值不熔断")
    void fastRealQueryResetsCounter() {
        Harness h = newHarness(2);
        h.op(SLOW_MS);          // 未命中 → 慢 → 1
        h.op(0);                // 未命中 → 快(0ms) → 真实成功 → 重置为 0
        h.op(SLOW_MS + 1);      // 未命中 → 慢 → 1（若上一步未重置，则会是 2 并熔断）
        assertEquals(0, h.openCircuits(),
                "真实快查询应重置连续计数，因此到此仅累计 1 次、未达阈值，不应熔断");
    }

    // ---------------- 测试脚手架 ----------------

    private static Harness newHarness(int failureThreshold) {
        SqlCircuitBreakerProperties props = new SqlCircuitBreakerProperties();
        props.setEnabled(true);
        props.setSelect(cfg(TIMEOUT_MS, failureThreshold));
        props.setInsert(cfg(5000L, 1));
        props.setUpdate(cfg(5000L, 1));
        props.setDelete(cfg(5000L, 1));

        PooledDataSource ds = new PooledDataSource("org.h2.Driver", URL, "sa", "");
        Configuration config = new Configuration(new Environment("test", new JdbcTransactionFactory(), ds));
        config.setCacheEnabled(true);

        CircuitBreakerRegistry registry = new CircuitBreakerRegistry(props);
        SqlCircuitBreakerInterceptor interceptor = new SqlCircuitBreakerInterceptor(
                props, registry, new NoOpMessageCenterClient(), new ConfigResolver(props),
                "test-app", new DefaultDataSourceKeyResolver(), new NoOpCircuitBreakerMetrics());
        config.addInterceptor(interceptor);
        config.addMapper(SlowMapper.class);

        return new Harness(new SqlSessionFactoryBuilder().build(config), registry);
    }

    private static SqlCircuitBreakerProperties.SqlTypeConfig cfg(long timeoutMs, int threshold) {
        SqlCircuitBreakerProperties.SqlTypeConfig c = new SqlCircuitBreakerProperties.SqlTypeConfig();
        c.setTimeoutMs(timeoutMs);
        c.setFailureThreshold(threshold);
        c.setCircuitOpenMs(60000L);
        c.setCacheMaxSize(1000L);
        return c;
    }

    /** 每个用例独立持有一份 Configuration(独立二级缓存) + Registry(独立熔断状态)，保证用例间隔离。 */
    private static final class Harness {
        final SqlSessionFactory factory;
        final CircuitBreakerRegistry registry;

        Harness(SqlSessionFactory factory, CircuitBreakerRegistry registry) {
            this.factory = factory;
            this.registry = registry;
        }

        /** 模拟一次独立「请求」：开/关 SqlSession 各一次，关闭时二级缓存才会落盘，从而下次同参命中。 */
        Integer op(int ms) {
            try (SqlSession s = factory.openSession()) {
                return s.getMapper(SlowMapper.class).slow(ms);
            }
        }

        boolean opThrowsCircuitOpen(int ms) {
            try (SqlSession s = factory.openSession()) {
                s.getMapper(SlowMapper.class).slow(ms);
                return false;
            } catch (Exception e) {
                for (Throwable t = e; t != null; t = t.getCause()) {
                    if (t instanceof SqlCircuitBreakerException) {
                        return true;
                    }
                }
                return false;
            }
        }

        long openCircuits() {
            return registry.countOpenCircuits();
        }
    }
}
