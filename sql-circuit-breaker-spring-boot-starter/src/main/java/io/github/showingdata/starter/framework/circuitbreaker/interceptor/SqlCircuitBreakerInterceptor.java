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
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.core.Ordered;

import java.sql.Statement;
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
 * SQL 超时熔断拦截器（混合层级设计）。
 *
 * <p>拦截点分工，目的是兼顾「连接池保护」与「缓存命中不污染熔断状态」：
 * <ul>
 *   <li><b>{@code StatementHandler.query}（缓存之下）</b>：只有真实落库的 SELECT 才会到达此层，
 *       MyBatis 一级/二级缓存命中会在上层直接返回、根本不会经过这里。
 *       因此把 SELECT 的<b>耗时统计与熔断计数</b>放在这里，
 *       从根本上避免「缓存命中被当成一次快速成功、清零连续超时计数」导致熔断永不触发的问题。</li>
 *   <li><b>{@code Executor.query}（缓存与连接之上）</b>：SELECT 仅在此做 <b>OPEN 快速失败</b>，
 *       在「申请数据库连接」之前就拒绝请求，最大化保护连接池（避免连接池被打满时连 fast-fail 都要排队等连接）。</li>
 *   <li><b>{@code Executor.update}</b>：DML（INSERT/UPDATE/DELETE）不走读缓存，
 *       在此一站式完成「快速失败 + 耗时统计 + 计数」，行为与历史版本一致。</li>
 * </ul>
 *
 * @author chenjiang
 */
@Intercepts({@Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class, CacheKey.class, BoundSql.class}),
        @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}),
        @Signature(type = StatementHandler.class, method = "query", args = {Statement.class, ResultHandler.class})
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

    /**
     * 单次拦截的熔断上下文快照：熔断 key、SQL 指纹、合并后的配置、状态机。
     * 由 {@link #prepareGuard} 一次性计算，供快速失败与耗时计数复用。
     */
    private static final class Guard {
        final String circuitKey;
        final String fingerprint;
        final ResolvedConfig config;
        final CircuitBreakerState state;

        Guard(String circuitKey, String fingerprint, ResolvedConfig config, CircuitBreakerState state) {
            this.circuitKey = circuitKey;
            this.fingerprint = fingerprint;
            this.config = config;
            this.state = state;
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
        // 全局开关未开启，直接放行，不做任何熔断处理
        if (!props.isEnabled()) {
            return invocation.proceed();
        }
        // 按拦截目标分发：
        // - StatementHandler.query 位于缓存之下，只有真实落库的 SELECT 才会到达，在此做耗时统计与计数；
        // - Executor.query / update 位于缓存与连接之上，SELECT 仅做 OPEN 快速失败，DML 做完整处理。
        if (invocation.getTarget() instanceof StatementHandler) {
            return onStatementHandlerQuery(invocation, (StatementHandler) invocation.getTarget());
        }
        return onExecutor(invocation);
    }

    /**
     * Executor 层拦截：
     * - SELECT：仅做 OPEN 快速失败（在申请连接、查询缓存之前拒绝，最大化保护连接池），
     *   耗时统计与计数留给 {@link #onStatementHandlerQuery}（缓存之下）完成；
     * - DML（INSERT/UPDATE/DELETE）：不走读缓存，在此一站式完成快速失败 + 耗时统计 + 计数。
     */
    private Object onExecutor(Invocation invocation) throws Throwable {
        MappedStatement ms = (MappedStatement) invocation.getArgs()[0];
        SqlCommandType sqlType = ms.getSqlCommandType();
        // 仅对 SELECT/INSERT/UPDATE/DELETE 生效，UNKNOWN/FLUSH 直接放行
        if (sqlType == SqlCommandType.UNKNOWN || sqlType == SqlCommandType.FLUSH) {
            return invocation.proceed();
        }
        metrics.recordIntercept(sqlType.name());

        // 6 参数重载时 args[5] 已是 BoundSql，其余情况通过参数对象动态获取
        Object[] args = invocation.getArgs();
        BoundSql boundSql = args.length == 6 ? (BoundSql) args[5] : ms.getBoundSql(args[1]);

        Guard guard = prepareGuard(ms, sqlType, boundSql);
        // 注解或 ThreadLocal 声明 disableCircuitBreaker=true，跳过熔断直接放行
        if (guard == null) {
            return invocation.proceed();
        }

        // OPEN 状态：在申请连接、查询缓存之前快速失败，保住连接池
        fastFailIfOpen(ms, sqlType, guard);

        if (sqlType == SqlCommandType.SELECT) {
            // SELECT 的耗时统计与计数下沉到 StatementHandler 层（缓存之下），此处仅放行
            return invocation.proceed();
        }
        // DML 不走读缓存，直接在 Executor 层完成计时与计数
        return proceedAndAccount(invocation, ms, sqlType, guard);
    }

    /**
     * StatementHandler 层拦截：只有真实落库的 SELECT 才会到达（缓存命中已在上层返回）。
     * 仅做耗时统计与熔断计数；OPEN 快速失败已在 {@link #onExecutor} 中完成。
     * <p>
     * 这是修复「缓存命中清零连续超时计数」的关键：缓存命中不经过此层，
     * 故既不会被计数，也不会触发 onSuccess() 重置，熔断计数只反映真实落库执行。
     */
    private Object onStatementHandlerQuery(Invocation invocation, StatementHandler handler) throws Throwable {
        MappedStatement ms = resolveMappedStatement(handler);
        // 极端定制场景反射拿不到 MappedStatement 时不介入，保证 SDK 无侵入
        if (ms == null) {
            return invocation.proceed();
        }
        SqlCommandType sqlType = ms.getSqlCommandType();
        if (sqlType == SqlCommandType.UNKNOWN || sqlType == SqlCommandType.FLUSH) {
            return invocation.proceed();
        }
        Guard guard = prepareGuard(ms, sqlType, handler.getBoundSql());
        if (guard == null) {
            return invocation.proceed();
        }
        return proceedAndAccount(invocation, ms, sqlType, guard);
    }

    /**
     * 执行 SQL 并按耗时更新熔断计数：
     * 耗时超阈值则累加连续失败次数（达到 failureThreshold 触发 OPEN）；否则重置连续失败计数。
     */
    private Object proceedAndAccount(Invocation invocation, MappedStatement ms,
                                     SqlCommandType sqlType, Guard guard) throws Throwable {
        long start = System.nanoTime();
        Object result = invocation.proceed();
        long cost = (System.nanoTime() - start) / 1000000;
        if (cost > guard.config.getTimeout()) {
            // 耗时超过阈值：累加失败次数，达到 failureThreshold 则触发熔断
            handleTimeout(ms, sqlType, guard.circuitKey, guard.fingerprint, cost, guard.config, guard.state);
        } else {
            // 正常成功：重置连续失败计数，保证 failureThreshold 是"连续"语义
            guard.state.onSuccess();
        }
        return result;
    }

    /**
     * 熔断 OPEN 且未到期：记录快速失败日志/指标并抛出 SqlCircuitBreakerException，不执行 SQL。
     */
    private void fastFailIfOpen(MappedStatement ms, SqlCommandType sqlType, Guard guard) {
        CircuitBreakerState state = guard.state;
        if (state.isOpen()) {
            String openAt = formatTs(state.getOpenTimestamp());
            // 快速失败日志节流：同一 circuitKey 每 5 秒只输出一次，防止高并发下日志风暴
            if (state.shouldLogFastFail(5000)) {
                log.error("[SqlCircuitBreaker] 快速失败 | key={} | mapper={} | sql={} | 熔断时间={} | 熔断时长={}ms",
                        guard.circuitKey, ms.getId(), guard.fingerprint, openAt, state.getCircuitOpenMs());
            }
            metrics.recordFastFail(sqlType.name(), ms.getId());
            throw new SqlCircuitBreakerException(
                    buildFailMessage(ms, guard.circuitKey, guard.fingerprint, sqlType, state, openAt), guard.circuitKey);
        }
    }

    /**
     * 计算单次拦截的熔断上下文：SQL 指纹、熔断 key、合并后的配置、状态机。
     * 返回 {@code null} 表示该 SQL 声明了 disableCircuitBreaker=true，调用方应直接放行。
     * <p>
     * SELECT 会在 Executor 层与 StatementHandler 层各调用一次本方法，
     * 指纹与注解均有缓存、计算无副作用，两次得到一致的 circuitKey 与同一个 state 实例。
     */
    private Guard prepareGuard(MappedStatement ms, SqlCommandType sqlType, BoundSql boundSql) {
        // 提取 SQL 指纹（将参数值替换为 ?，归一化同类 SQL），优先查缓存跳过正则 + MD5
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
        // 熔断 key = 数据源ID + SQL类型 + 指纹的 MD5，数据源ID 隔离多数据源场景下的熔断状态
        String dsKey = dataSourceKeyResolver.resolve(ms);
        String circuitKey = (dsKey != null ? dsKey : "default") + ":" + sqlType.name() + ":" + fingerprintHash;

        // 按优先级解析配置（ThreadLocal 快照 > 方法注解 > 接口注解 > 全局配置）
        SqlCircuitBreakerConfig tlSnapshot = SqlCircuitBreakerContext.get();
        ResolvedConfig config = configResolver.resolve(ms, sqlType, tlSnapshot);
        if (config.isDisableCircuitBreaker()) {
            return null;
        }
        CircuitBreakerState state = registry.getOrCreate(circuitKey, sqlType);
        return new Guard(circuitKey, fingerprint, config, state);
    }

    /**
     * 从 StatementHandler 反射取出 MappedStatement。
     * 默认实现 RoutingStatementHandler 持有 delegate(BaseStatementHandler)，其中含 mappedStatement；
     * 兜底兼容直接为 BaseStatementHandler 的情况。取不到时返回 null，调用方放行不介入。
     */
    private MappedStatement resolveMappedStatement(StatementHandler handler) {
        MetaObject meta = SystemMetaObject.forObject(handler);
        if (meta.hasGetter("delegate.mappedStatement")) {
            return (MappedStatement) meta.getValue("delegate.mappedStatement");
        }
        if (meta.hasGetter("mappedStatement")) {
            return (MappedStatement) meta.getValue("mappedStatement");
        }
        return null;
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
