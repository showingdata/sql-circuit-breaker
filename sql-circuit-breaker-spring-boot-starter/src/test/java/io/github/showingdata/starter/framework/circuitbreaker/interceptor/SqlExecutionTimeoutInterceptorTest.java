package io.github.showingdata.starter.framework.circuitbreaker.interceptor;

import io.github.showingdata.starter.framework.circuitbreaker.config.SqlCircuitBreakerProperties;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Invocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SqlExecutionTimeoutInterceptor 单测：秒级换算 / 开关 / min-with-current / 容错 / types 过滤 / 上下文缺失 / prepare 异常。
 * 通过 SqlExecutionTimeoutContext 模拟 Executor 层已解析出的最终熔断配置。
 */
class SqlExecutionTimeoutInterceptorTest {

    private SqlCircuitBreakerProperties props;
    private StatementHandler handler;
    private Statement stmt;
    private Connection conn;
    private Invocation invocation;

    private SqlExecutionTimeoutInterceptor newInterceptor() {
        return new SqlExecutionTimeoutInterceptor(props);
    }

    @BeforeEach
    void setUp() throws Exception {
        props = new SqlCircuitBreakerProperties();
        props.setEnabled(true);
        handler = mock(StatementHandler.class);
        stmt = mock(Statement.class);
        conn = mock(Connection.class);
        invocation = new Invocation(handler,
                StatementHandler.class.getMethod("prepare", Connection.class, Integer.class),
                new Object[]{conn, 0});
        when(handler.prepare(conn, 0)).thenReturn(stmt);
        when(stmt.getQueryTimeout()).thenReturn(0);
    }

    private void enable() {
        props.getExecutionTimeout().setEnabled(true);
    }

    private Object interceptWithContext(SqlCommandType sqlType, long timeoutMs) throws Throwable {
        SqlExecutionTimeoutContext.Entry previous = SqlExecutionTimeoutContext.set(sqlType, timeoutMs);
        try {
            return newInterceptor().intercept(invocation);
        } finally {
            SqlExecutionTimeoutContext.restore(previous);
        }
    }

    // ---------- 秒级换算 ----------

    @Test
    void timeoutMs_30000_applies30Seconds() throws Throwable {
        enable();
        interceptWithContext(SqlCommandType.SELECT, 30000L);
        verify(stmt).setQueryTimeout(30);
    }

    @Test
    void timeoutMs_1500_roundsUpTo2Seconds() throws Throwable {
        enable();
        interceptWithContext(SqlCommandType.SELECT, 1500L);
        verify(stmt).setQueryTimeout(2);
    }

    @Test
    void timeoutMs_1000_exactly1Second() throws Throwable {
        enable();
        interceptWithContext(SqlCommandType.SELECT, 1000L);
        verify(stmt).setQueryTimeout(1);
    }

    @Test
    void timeoutMs_500_minimum1Second() throws Throwable {
        enable();
        interceptWithContext(SqlCommandType.SELECT, 500L);
        verify(stmt).setQueryTimeout(1);
    }

    // ---------- 开关 ----------

    @Test
    void disabled_doesNotTouchStatement() throws Throwable {
        // enabled 默认 false
        Object result = interceptWithContext(SqlCommandType.SELECT, 30000L);
        assertSame(stmt, result);
        verify(stmt, never()).setQueryTimeout(anyInt());
    }

    @Test
    void enabledButNoExecutorContext_doesNotTouchStatement() throws Throwable {
        enable();
        Object result = newInterceptor().intercept(invocation);
        assertSame(stmt, result);
        verify(stmt, never()).setQueryTimeout(anyInt());
    }

    // ---------- min-with-current ----------

    @Test
    void existingTighterTimeout_respected_withoutReset() throws Throwable {
        // XML timeout=10s 更紧：target=min(10,30)=10 == current → 不重复 set，直接尊重既有超时
        enable();
        when(stmt.getQueryTimeout()).thenReturn(10);
        interceptWithContext(SqlCommandType.SELECT, 30000L);
        verify(stmt, never()).setQueryTimeout(anyInt());
    }

    @Test
    void configTighterThanExisting_setsTighterTimeout() throws Throwable {
        // 既有 50s、配置 30s：收紧到 30s
        enable();
        when(stmt.getQueryTimeout()).thenReturn(50);
        interceptWithContext(SqlCommandType.SELECT, 30000L);
        verify(stmt).setQueryTimeout(30);
    }

    @Test
    void noExistingTimeout_setsConfigured() throws Throwable {
        enable();
        interceptWithContext(SqlCommandType.SELECT, 30000L);
        verify(stmt).setQueryTimeout(30);
    }

    // ---------- 容错 ----------

    @Test
    void setQueryTimeoutThrows_swallowedAndStatementReturned() throws Throwable {
        enable();
        doThrow(new SQLException("driver unsupported")).when(stmt).setQueryTimeout(anyInt());
        Object result = interceptWithContext(SqlCommandType.SELECT, 30000L);
        assertSame(stmt, result);
    }

    // ---------- types 过滤 ----------

    @Test
    void updateType_configuredSelectOnly_notApplied() throws Throwable {
        enable();
        props.getExecutionTimeout().setTypes(Collections.singletonList("SELECT"));
        interceptWithContext(SqlCommandType.UPDATE, 30000L);
        verify(stmt, never()).setQueryTimeout(anyInt());
    }

    @Test
    void selectType_matchesConfiguredSelect_applied() throws Throwable {
        enable();
        props.getExecutionTimeout().setTypes(Collections.singletonList("SELECT"));
        interceptWithContext(SqlCommandType.SELECT, 30000L);
        verify(stmt).setQueryTimeout(30);
    }

    @Test
    void defaultTypes_emptyMeansAllTypes() throws Throwable {
        // types 未配置（null）→ 全部四种生效，UPDATE 也命中
        enable();
        interceptWithContext(SqlCommandType.UPDATE, 30000L);
        verify(stmt).setQueryTimeout(30);
    }

    // ---------- prepare 异常 ----------

    @Test
    void prepareThrows_propagates() throws Throwable {
        enable();
        when(handler.prepare(conn, 0)).thenThrow(new SQLException("db down"));
        Throwable thrown = assertThrows(Throwable.class,
                () -> interceptWithContext(SqlCommandType.SELECT, 30000L));
        // 真实 MyBatis 拦截链经 Plugin.invoke unwrap，最终抛原始 SQLException；
        // 单测直调 intercept 拿到反射包装的 InvocationTargetException，检查 cause 即原始异常
        Throwable cause = thrown instanceof java.lang.reflect.InvocationTargetException ? thrown.getCause() : thrown;
        assertTrue(cause instanceof SQLException, "prepare 异常应原样上抛，实际：" + thrown);
    }

    // ---------- 配置 fail-fast ----------

    @Test
    void invalidTypeValue_failsFastOnConstruction() {
        props.getExecutionTimeout().setTypes(Collections.singletonList("BAD_TYPE"));
        assertThrows(IllegalStateException.class, () -> new SqlExecutionTimeoutInterceptor(props));
    }
}
