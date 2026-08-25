package io.github.showingdata.starter.framework.circuitbreaker.timeout;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DefaultSqlTimeoutExceptionClassifier 单测：覆盖 JDBC 标准异常、SQLState、
 * 异常链穿透、非超时异常、null 输入等关键路径。
 *
 * <p>类名关键词匹配用 {@link SQLTimeoutException}（JDBC 标准）模拟，避免引入 driver 依赖。
 */
class DefaultSqlTimeoutExceptionClassifierTest {

    private final DefaultSqlTimeoutExceptionClassifier classifier = new DefaultSqlTimeoutExceptionClassifier();

    // ---------- JDBC 标准异常 ----------

    @Test
    void jdbcSqlTimeoutException_matched() {
        assertTrue(classifier.isTimeoutOrCancelled(new SQLTimeoutException("query timed out after 10s")));
    }

    @Test
    void sqlExceptionWithHyT00State_matched() {
        SQLException e = new SQLException("Query timeout", "HYT00");
        assertTrue(classifier.isTimeoutOrCancelled(e));
    }

    @Test
    void sqlExceptionWithHy008State_matched() {
        // MySQL 用的 operation canceled SQLState
        SQLException e = new SQLException("Operation canceled", "HY008");
        assertTrue(classifier.isTimeoutOrCancelled(e));
    }

    @Test
    void sqlExceptionWith57014State_matched() {
        // PostgreSQL query_canceled
        SQLException e = new SQLException("ERROR: canceling statement due to user request", "57014");
        assertTrue(classifier.isTimeoutOrCancelled(e));
    }

    // ---------- 类名关键词匹配 ----------

    @Test
    void genericRuntimeTimeoutException_notMatchedByClassName() {
        // 通用并发超时不是 JDBC/driver SQL 超时，避免异常分类过宽导致误熔断。
        assertFalse(classifier.isTimeoutOrCancelled(new TimeoutException()));
    }

    // ---------- 异常链穿透 ----------

    @Test
    void timeoutWrappedInRuntimeException_matchedThroughCauseChain() {
        // 模拟 Spring / MyBatis / 连接池把 driver 超时异常层层包装
        Throwable wrapped = new RuntimeException("MyBatis invocation failed",
                new SQLException("Query timeout", "HYT00"));
        assertTrue(classifier.isTimeoutOrCancelled(wrapped));
    }

    @Test
    void deeplyNestedTimeout_matchedThroughCauseChain() {
        // 三层包装：业务异常 → Spring 包装 → driver 超时
        Throwable driverTimeout = new SQLTimeoutException("server timeout");
        Throwable springWrap = new RuntimeException("SqlSessionTemplate error", driverTimeout);
        Throwable bizWrap = new IllegalStateException("service call failed", springWrap);
        assertTrue(classifier.isTimeoutOrCancelled(bizWrap));
    }

    @Test
    void causeChainWithoutTimeout_notMatched() {
        Throwable e = new RuntimeException("wrapper",
                new SQLException("connection refused", "08001"));
        assertFalse(classifier.isTimeoutOrCancelled(e));
    }

    // ---------- 非超时异常 ----------

    @Test
    void sqlExceptionWithUnrelatedSqlState_notMatched() {
        SQLException e = new SQLException("ORA-00001: unique constraint violated", "23000");
        assertFalse(classifier.isTimeoutOrCancelled(e));
    }

    @Test
    void runtimeExceptionWithoutTimeoutHint_notMatched() {
        assertFalse(classifier.isTimeoutOrCancelled(new NullPointerException("stmt is null")));
    }

    @Test
    void connectionException_notMatched() {
        SQLException e = new SQLException("Connection is closed", "08007");
        assertFalse(classifier.isTimeoutOrCancelled(e));
    }

    // ---------- 边界 ----------

    @Test
    void nullInput_returnsFalse() {
        assertFalse(classifier.isTimeoutOrCancelled(null));
    }

    @Test
    void selfReferencingCauseChain_jdkPreventsConstruction() {
        // JDK 的 Throwable.initCause 禁止自循环（抛 IllegalArgumentException），
        // 所以 classifier 的 depth 上限是双重保险——这里验证 JDK 层面已拦住，
        // 极端自循环场景在真实代码中无法构造，classifier 不会陷入死循环。
        SQLException e = new SQLException("bad driver", "HYT00");
        assertThrows(IllegalArgumentException.class, () -> e.initCause(e));
    }

    @Test
    void veryDeepCauseChainWithTimeoutAtBottom_matched() {
        // 构造深度超过 MAX_CAUSE_DEPTH=20 的链，超时异常在第 5 层（应在截断前命中）
        Throwable bottom = new SQLTimeoutException("real timeout");
        Throwable cur = bottom;
        for (int i = 0; i < 5; i++) {
            cur = new RuntimeException("layer " + i, cur);
        }
        assertTrue(classifier.isTimeoutOrCancelled(cur));
    }

    @Test
    void veryDeepCauseChainWithTimeoutAtDepth25_notMatchedDueToDepthLimit() {
        // 超时异常在第 25 层（超过 MAX_CAUSE_DEPTH=20 截断），不应被识别
        // 这是保守行为：极端深包装下放弃识别，避免性能问题
        Throwable bottom = new SQLTimeoutException("deep timeout");
        Throwable cur = bottom;
        for (int i = 0; i < 25; i++) {
            cur = new RuntimeException("layer " + i, cur);
        }
        assertFalse(classifier.isTimeoutOrCancelled(cur));
    }
}
