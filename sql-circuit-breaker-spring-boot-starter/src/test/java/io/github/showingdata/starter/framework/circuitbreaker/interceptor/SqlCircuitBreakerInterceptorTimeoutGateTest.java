package io.github.showingdata.starter.framework.circuitbreaker.interceptor;

import io.github.showingdata.starter.framework.circuitbreaker.CircuitBreakerState;
import io.github.showingdata.starter.framework.circuitbreaker.config.ConfigResolver;
import io.github.showingdata.starter.framework.circuitbreaker.config.ResolvedConfig;
import io.github.showingdata.starter.framework.circuitbreaker.config.SqlCircuitBreakerProperties;
import io.github.showingdata.starter.framework.circuitbreaker.datasource.DataSourceKeyResolver;
import io.github.showingdata.starter.framework.circuitbreaker.keystrategy.CircuitBreakerKeyContext;
import io.github.showingdata.starter.framework.circuitbreaker.keystrategy.CircuitBreakerKeyStrategy;
import io.github.showingdata.starter.framework.circuitbreaker.message.MessageCenterClient;
import io.github.showingdata.starter.framework.circuitbreaker.metrics.SqlCircuitBreakerMetrics;
import io.github.showingdata.starter.framework.circuitbreaker.registry.CircuitBreakerRegistry;
import io.github.showingdata.starter.framework.circuitbreaker.timeout.SqlTimeoutExceptionClassifier;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.sql.SQLTimeoutException;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 门控测试：driver 硬超时/取消异常是否计入熔断失败，必须由 execution-timeout.enabled 控制。
 *
 * <p>这是 3.0.2 向后兼容的关键防线——默认（execution-timeout 关闭）存量业务遇到的任何异常
 * （含连接池 queryTimeout、DB statement_timeout、驱动取消等被 classifier 识别为超时的异常）
 * 都保持「不对异常熔断」原语义；仅当用户显式开启 execution-timeout 后，driver 硬超时
 * 才会经 {@link SqlTimeoutExceptionClassifier} 计入连续失败计数。</p>
 */
class SqlCircuitBreakerInterceptorTimeoutGateTest {

    private SqlCircuitBreakerProperties props;
    private CircuitBreakerRegistry registry;
    private CircuitBreakerState state;
    private ConfigResolver configResolver;
    private DataSourceKeyResolver dataSourceKeyResolver;
    private SqlCircuitBreakerMetrics metrics;
    private CircuitBreakerKeyStrategy keyStrategy;
    private SqlTimeoutExceptionClassifier classifier;
    private Invocation invocation;
    private MappedStatement ms;

    private SQLTimeoutException driverTimeout;
    private InvocationTargetException wrapped;

    @BeforeEach
    void setUp() {
        props = mock(SqlCircuitBreakerProperties.class);
        when(props.isEnabled()).thenReturn(true);

        registry = mock(CircuitBreakerRegistry.class);
        state = mock(CircuitBreakerState.class);
        when(state.isOpen()).thenReturn(false);
        when(state.onTimeout(anyInt(), anyLong())).thenReturn(false);
        when(registry.getOrCreate("key-1", SqlCommandType.SELECT)).thenReturn(state);

        configResolver = mock(ConfigResolver.class);
        when(configResolver.resolve(any(MappedStatement.class), any(SqlCommandType.class), any()))
                .thenReturn(ResolvedConfig.builder()
                        .timeout(1000L)
                        .circuitOpenMs(60000L)
                        .failureThreshold(3)
                        .disableCircuitBreaker(false)
                        .build());

        dataSourceKeyResolver = mock(DataSourceKeyResolver.class);
        when(dataSourceKeyResolver.resolve(any(MappedStatement.class))).thenReturn("ds-1");

        metrics = mock(SqlCircuitBreakerMetrics.class);

        keyStrategy = mock(CircuitBreakerKeyStrategy.class);
        when(keyStrategy.resolve(any(CircuitBreakerKeyContext.class))).thenReturn("key-1");

        // classifier 默认 mock 返回 false（非超时）；各用例按需覆盖 stub 为 true
        classifier = mock(SqlTimeoutExceptionClassifier.class);

        // MappedStatement 是 final 类不可 mock，用真实构造（Builder + mock SqlSource）
        Configuration configuration = new Configuration();
        SqlSource sqlSource = mock(SqlSource.class);
        BoundSql boundSql = mock(BoundSql.class);
        when(boundSql.getSql()).thenReturn("select * from t where id = ?");
        when(sqlSource.getBoundSql(any())).thenReturn(boundSql);
        ms = new MappedStatement.Builder(configuration, "com.example.mapper.selectById", sqlSource, SqlCommandType.SELECT).build();

        Object[] args = {ms, new Object(), new RowBounds(), null};
        invocation = mock(Invocation.class);
        when(invocation.getArgs()).thenReturn(args);

        // 模拟真实链路：Executor 层 invocation.proceed() 抛 InvocationTargetException，driver 异常包在 cause 里
        driverTimeout = new SQLTimeoutException("Query timeout");
        wrapped = new InvocationTargetException(driverTimeout);
    }

    private SqlCircuitBreakerInterceptor build(boolean executionTimeoutEnabled) {
        return build(executionTimeoutEnabled, null);
    }

    private SqlCircuitBreakerInterceptor build(boolean executionTimeoutEnabled, List<String> executionTimeoutTypes) {
        SqlCircuitBreakerProperties.ExecutionTimeoutConfig cfg = new SqlCircuitBreakerProperties.ExecutionTimeoutConfig();
        cfg.setEnabled(executionTimeoutEnabled);
        cfg.setTypes(executionTimeoutTypes);
        when(props.getExecutionTimeout()).thenReturn(cfg);
        return new SqlCircuitBreakerInterceptor(props, registry, mock(MessageCenterClient.class),
                configResolver, "test-app", dataSourceKeyResolver, metrics, keyStrategy, classifier);
    }

    @Test
    void driverTimeout_notCounted_whenExecutionTimeoutDisabled() throws Throwable {
        when(classifier.isTimeoutOrCancelled(any())).thenReturn(true);
        SqlCircuitBreakerInterceptor interceptor = build(false);

        Throwable thrown = assertThrows(InvocationTargetException.class,
                () -> invocationProceedThrows(interceptor));
        assertSame(wrapped, thrown);

        // 关键断言：execution-timeout 关闭时，即使 classifier 识别为超时，也绝不累计熔断失败
        verify(state, never()).onTimeout(anyInt(), anyLong());
        assertNull(SqlExecutionTimeoutContext.get());
    }

    @Test
    void driverTimeout_counted_whenExecutionTimeoutEnabled() throws Throwable {
        when(classifier.isTimeoutOrCancelled(any())).thenReturn(true);
        SqlCircuitBreakerInterceptor interceptor = build(true);

        Throwable thrown = assertThrows(InvocationTargetException.class,
                () -> invocationProceedThrows(interceptor));
        assertSame(wrapped, thrown);

        // execution-timeout 开启 + classifier 识别 → 计入一次失败
        verify(state, times(1)).onTimeout(anyInt(), anyLong());
        assertNull(SqlExecutionTimeoutContext.get());
    }

    @Test
    void nonTimeoutException_neverCounted_evenWhenExecutionTimeoutEnabled() throws Throwable {
        // classifier 判定为非超时（如连接异常、约束违反）→ 即使 execution-timeout 开启也不计数
        when(classifier.isTimeoutOrCancelled(any())).thenReturn(false);
        SqlCircuitBreakerInterceptor interceptor = build(true);

        Throwable thrown = assertThrows(InvocationTargetException.class,
                () -> invocationProceedThrows(interceptor));
        assertSame(wrapped, thrown);

        verify(state, never()).onTimeout(anyInt(), anyLong());
        assertNull(SqlExecutionTimeoutContext.get());
    }

    @Test
    void driverTimeout_notCounted_whenExecutionTimeoutTypeDoesNotMatch() throws Throwable {
        when(classifier.isTimeoutOrCancelled(any())).thenReturn(true);
        SqlCircuitBreakerInterceptor interceptor = build(true, Collections.singletonList("UPDATE"));

        Throwable thrown = assertThrows(InvocationTargetException.class,
                () -> invocationProceedThrows(interceptor));
        assertSame(wrapped, thrown);

        verify(state, never()).onTimeout(anyInt(), anyLong());
        assertNull(SqlExecutionTimeoutContext.get());
    }

    @Test
    void executionTimeoutContext_restoredAfterSuccessfulSqlInvocation() throws Throwable {
        SqlExecutionTimeoutContext.Entry previous = SqlExecutionTimeoutContext.set(SqlCommandType.UPDATE, 5000L);
        try {
            SqlCircuitBreakerInterceptor interceptor = build(true);
            when(invocation.proceed()).thenReturn("ok");

            Object result = interceptor.intercept(invocation);

            assertSame("ok", result);
            assertEquals(SqlCommandType.UPDATE, SqlExecutionTimeoutContext.get().getSqlType());
            assertEquals(5000L, SqlExecutionTimeoutContext.get().getTimeoutMs());
        } finally {
            SqlExecutionTimeoutContext.restore(previous);
        }
    }

    /**
     * 让 invocation.proceed() 抛 InvocationTargetException 的辅助封装：
     * intercept 声明 throws Throwable，lambda 直接调需捕获，此处统一在断言中透传。
     */
    private void invocationProceedThrows(SqlCircuitBreakerInterceptor interceptor) throws Throwable {
        when(invocation.proceed()).thenThrow(wrapped);
        interceptor.intercept(invocation);
    }
}
