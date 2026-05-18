package io.github.showingdata.starter.framework.circuitbreaker.interceptor;

import io.github.showingdata.starter.framework.circuitbreaker.CircuitBreakerEvent;
import io.github.showingdata.starter.framework.circuitbreaker.CircuitBreakerState;
import io.github.showingdata.starter.framework.circuitbreaker.SqlCircuitBreakerException;
import io.github.showingdata.starter.framework.circuitbreaker.SqlFingerprintUtils;
import io.github.showingdata.starter.framework.circuitbreaker.context.SqlCircuitBreakerContext;
import io.github.showingdata.starter.framework.circuitbreaker.config.ConfigResolver;
import io.github.showingdata.starter.framework.circuitbreaker.config.ResolvedConfig;
import io.github.showingdata.starter.framework.circuitbreaker.config.SqlCircuitBreakerConfig;
import io.github.showingdata.starter.framework.circuitbreaker.config.SqlCircuitBreakerProperties;
import io.github.showingdata.starter.framework.circuitbreaker.datasource.DataSourceKeyResolver;
import io.github.showingdata.starter.framework.circuitbreaker.message.MessageCenterClient;
import io.github.showingdata.starter.framework.circuitbreaker.metrics.SqlCircuitBreakerMetrics;
import io.github.showingdata.starter.framework.circuitbreaker.registry.CircuitBreakerRegistry;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.core.Ordered;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @author chenjiang
 */
@Intercepts({@Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class, CacheKey.class, BoundSql.class}),
        @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class})
})
public class SqlCircuitBreakerInterceptor implements Interceptor, Ordered, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SqlCircuitBreakerInterceptor.class);

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 消息发送用独立守护线程，避免消息中心抖动阻塞 SQL 业务线程。
     * 单线程而非线程池：CIRCUIT_OPEN 为极低频事件，单线程绰绰有余，同时保证消息有序。
     * static 而非实例字段：拦截器是 Spring 单例，static 明确表达整个 JVM 只需一个发送线程的意图。
     * 守护线程：Spring 容器关闭时由 destroy() 触发优雅关闭，等待最多 3s 让队列中的消息发完；
     * 超时则强制中断，极端情况下未发完的消息会丢弃，这对告警通知是可接受的代价。
     * <p>
     * 使用有界队列（容量 1000），防止消息中心长时间不可用时内存无限增长导致 OOM。
     * 队列满时直接丢弃事件并记录 WARN 日志，不阻塞业务线程。
     */
    private static final ExecutorService MSG_EXECUTOR = new ThreadPoolExecutor(
            1, 1,
            0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1000),
            r -> {
                Thread t = new Thread(r, "sql-circuit-breaker-msg");
                t.setDaemon(true);
                return t;
            },
            (r, executor) -> log.warn("[SqlCircuitBreaker] 消息发送队列已满，事件被丢弃，请检查消息中心可用性")
    );

    private final SqlCircuitBreakerProperties props;
    private final CircuitBreakerRegistry registry;
    private final MessageCenterClient messageCenterClient;
    private final ConfigResolver configResolver;
    private final String applicationName;
    private final DataSourceKeyResolver dataSourceKeyResolver;
    private final SqlCircuitBreakerMetrics metrics;

    /**
     * SQL 指纹缓存：MappedStatement.id → FingerprintEntry。
     * 同一 Mapper 方法的 SQL 模板（BoundSql.getSql()）通常固定不变，
     * 缓存后跳过 6 轮正则替换 + MD5 计算，显著降低高 QPS 下的 CPU 开销。
     * 动态 SQL（<if>/<foreach>）场景下 rawSql 可能变化，通过 equals 校验保证正确性。
     */
    private final ConcurrentHashMap<String, FingerprintEntry> fingerprintCache = new ConcurrentHashMap<>();

    /**
     * SQL 指纹缓存条目，存储原始 SQL、提取后的指纹和 MD5 哈希。
     * 所有字段均为 final，不可变，保证多线程读取安全。
     */
    private static final class FingerprintEntry {
        final String rawSql;
        final String fingerprint;
        final String hash;

        FingerprintEntry(String rawSql, String fingerprint, String hash) {
            this.rawSql = rawSql;
            this.fingerprint = fingerprint;
            this.hash = hash;
        }
    }

    public SqlCircuitBreakerInterceptor(SqlCircuitBreakerProperties props,
                                        CircuitBreakerRegistry registry,
                                        MessageCenterClient messageCenterClient,
                                        ConfigResolver configResolver,
                                        String applicationName,
                                        DataSourceKeyResolver dataSourceKeyResolver,
                                        SqlCircuitBreakerMetrics metrics) {
        this.props = props;
        this.registry = registry;
        this.messageCenterClient = messageCenterClient;
        this.configResolver = configResolver;
        this.applicationName = applicationName;
        this.dataSourceKeyResolver = dataSourceKeyResolver;
        this.metrics = metrics;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        // 步骤1：全局开关未开启，直接放行，不做任何熔断处理
        if (!props.isEnabled()) {
            return invocation.proceed();
        }

        // 步骤2：获取 SQL 类型，仅对 SELECT/INSERT/UPDATE/DELETE 生效，UNKNOWN/FLUSH 直接放行
        MappedStatement ms = (MappedStatement) invocation.getArgs()[0];
        SqlCommandType sqlType = ms.getSqlCommandType();
        if (sqlType == SqlCommandType.UNKNOWN || sqlType == SqlCommandType.FLUSH) {
            return invocation.proceed();
        }

        metrics.recordIntercept(sqlType.name());

        // 步骤3：入口快照 ThreadLocal 配置，整次调用使用同一份快照。
        /**ThreadLocal 生命周期由调用方负责（在自己 finally 中 clear()），拦截器不再兜底清理，
         * 避免业务方在 Service 层 set 后调用多条 Mapper 时，从第二条 SQL 起 ThreadLocal 失效。
         * public void processOrders(List<Long> ids) {
         *       try {
         *           SqlCircuitBreakerContext.setTimeout(60_000);
         *           orderMapper.selectByIds(ids);        // ✅ 60s
         *           orderMapper.updateStatus(ids);       // ✅ 60s
         *           orderMapper.insertAuditLog(ids);     // ✅ 60s
         *       } finally {
         *           SqlCircuitBreakerContext.clear();    // 调用方负责
         *       }
         *   }
         */
        SqlCircuitBreakerConfig tlSnapshot = SqlCircuitBreakerContext.get();

        // 步骤4：获取 BoundSql —— 6 参数重载时 args[5] 已是 BoundSql，其余情况通过参数对象动态获取
        BoundSql boundSql = invocation.getArgs().length == 6 ? (BoundSql) invocation.getArgs()[5] : ms.getBoundSql(invocation.getArgs()[1]);

        // 步骤5：提取 SQL 指纹（将参数值替换为 ?，归一化同类 SQL），用于日志展示
        // 优先查缓存：同一 Mapper 方法的 SQL 模板通常固定，缓存命中可跳过 6 轮正则 + MD5 计算；
        // 动态 SQL（<if>/<foreach>）场景下 rawSql 可能变化，equals 不匹配时重新计算并更新缓存。
        String rawSql = boundSql.getSql();
        FingerprintEntry entry = fingerprintCache.get(ms.getId());
        String fingerprint;
        String fingerprintHash;
        if (entry != null && Objects.equals(rawSql, entry.rawSql)) {
            fingerprint = entry.fingerprint;
            fingerprintHash = entry.hash;
        } else {
            fingerprint = SqlFingerprintUtils.extract(rawSql);
            fingerprintHash = SqlFingerprintUtils.hash(fingerprint);
            fingerprintCache.put(ms.getId(), new FingerprintEntry(rawSql, fingerprint, fingerprintHash));
        }

        // 步骤6：生成熔断 key = 数据源ID + SQL类型 + 指纹的 MD5，数据源ID 隔离多数据源场景下的熔断状态
        String dsKey = dataSourceKeyResolver.resolve(ms);
        String circuitKey = (dsKey != null ? dsKey : "default") + ":" + sqlType.name() + ":" + fingerprintHash;

        // 步骤7：按优先级解析配置（ThreadLocal 快照 > 方法注解 > 接口注解 > 全局配置）
        ResolvedConfig config = configResolver.resolve(ms, sqlType, tlSnapshot);

        // 步骤8：注解或 ThreadLocal 声明了 disableCircuitBreaker=true，跳过熔断直接放行
        if (config.isDisableCircuitBreaker()) {
            return invocation.proceed();
        }

        // 步骤9：从注册表获取（或初始化）该 SQL 对应的熔断状态机
        CircuitBreakerState state = registry.getOrCreate(circuitKey, sqlType);

        // 步骤10：熔断器处于 OPEN 状态，快速失败，不再执行 SQL
        if (state.isOpen()) {
            String openAt = formatTs(state.getOpenTimestamp());
            // 快速失败日志节流：同一 circuitKey 每 5 秒只输出一次，防止高并发下日志风暴
            if (state.shouldLogFastFail(5000)) {
                log.error("[SqlCircuitBreaker] 快速失败 | key={} | mapper={} | sql={} | 熔断时间={} | 熔断时长={}ms", circuitKey, ms.getId(), fingerprint, openAt, state.getCircuitOpenMs());
            }
            metrics.recordFastFail(sqlType.name(), ms.getId());
            throw new SqlCircuitBreakerException(buildFailMessage(ms, circuitKey, fingerprint, sqlType, state, openAt), circuitKey);
        }

        // 步骤11：执行实际 SQL，并统计耗时
        long start = System.nanoTime();
        Object result = invocation.proceed();
        long cost = (System.nanoTime() - start) / 1000000;

        // 步骤12：SQL 执行成功后，根据耗时更新熔断计数
        if (cost > config.getTimeout()) {
            // 耗时超过阈值：累加失败次数，达到 failureThreshold 则触发熔断
            handleTimeout(ms, sqlType, circuitKey, fingerprint, cost, config, state);
        } else {
            // 正常成功：重置连续失败计数，保证 failureThreshold 是"连续"语义
            state.onSuccess();
        }
        return result;
    }

    private void handleTimeout(MappedStatement ms, SqlCommandType sqlType,
                               String circuitKey, String fingerprint,
                               long cost, ResolvedConfig config, CircuitBreakerState state) {
        log.error("[SqlCircuitBreaker] 执行超时 | key={} | mapper={} | sql={} | 耗时={}ms | 超时阈值={}ms", circuitKey, ms.getId(), fingerprint, cost, config.getTimeout());
        metrics.recordTimeout(sqlType.name(), ms.getId());
        boolean triggered = state.onTimeout(config.getFailureThreshold(), config.getCircuitOpenMs());
        if (triggered) {
            String openAt = formatTs(state.getOpenTimestamp());
            String recoverAt = formatTs(state.getOpenTimestamp() + state.getCircuitOpenMs());
            log.error("[SqlCircuitBreaker] 熔断开启 | key={} | 熔断时长={}ms | 开始={} | 预计恢复={}", circuitKey, state.getCircuitOpenMs(), openAt, recoverAt);
            metrics.recordOpen(sqlType.name(), ms.getId());
            sendMessage(buildEvent(ms, fingerprint, sqlType, state, config.getTimeout(), cost).setEventType("CIRCUIT_OPEN"));
        }
    }

    private CircuitBreakerEvent buildEvent(MappedStatement ms, String fingerprint,
                                           SqlCommandType sqlType, CircuitBreakerState state,
                                           long timeout, long cost) {
        return new CircuitBreakerEvent()
                .setApplicationName(applicationName)
                .setMapperId(ms.getId())
                .setSqlFingerprint(fingerprint)
                .setSqlType(sqlType.name())
                .setCost(cost)
                .setTimeoutThreshold(timeout)
                .setCircuitOpenMs(state.getCircuitOpenMs())
                .setEventTime(System.currentTimeMillis());
    }

    private String buildFailMessage(MappedStatement ms, String circuitKey, String fingerprint,
                                    SqlCommandType sqlType, CircuitBreakerState state, String openAt) {
        return String.format(
                "[SqlCircuitBreaker] 熔断已开启，请求快速失败\n"
                        + "  mapper   = %s\n"
                        + "  SQL类型  = %s\n"
                        + "  key      = %s\n"
                        + "  sql      = %s\n"
                        + "  熔断时间 = %s\n"
                        + "  熔断时长 = %dms",
                ms.getId(), sqlType.name(), circuitKey, fingerprint,
                openAt, state.getCircuitOpenMs());
    }

    private void sendMessage(CircuitBreakerEvent event) {
        try {
            MSG_EXECUTOR.execute(() -> {
                try {
                    messageCenterClient.send(event);
                } catch (Exception e) {
                    log.warn("[SqlCircuitBreaker] 消息发送失败，eventType={}", event.getEventType(), e);
                }
            });
        } catch (RejectedExecutionException e) {
            // 线程池已关闭（如应用关闭过程中），不影响主业务流
            log.warn("[SqlCircuitBreaker] 消息线程池已关闭，事件丢弃，eventType={}", event.getEventType());
        }
    }

    private String formatTs(long ts) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.systemDefault()).format(FMT);
    }

    @Override
    public int getOrder() {
        // 固定最低优先级，保证熔断器位于 MyBatis 拦截器链最外层（最先执行），不开放配置避免误用
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    @Override
    public void setProperties(Properties properties) {
    }

    @Override
    public void destroy() {
        MSG_EXECUTOR.shutdown();
        try {
            if (!MSG_EXECUTOR.awaitTermination(3, TimeUnit.SECONDS)) {
                MSG_EXECUTOR.shutdownNow();
                log.warn("[SqlCircuitBreaker] 消息线程未在 3s 内终止，已强制关闭");
            }
        } catch (InterruptedException e) {
            MSG_EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
