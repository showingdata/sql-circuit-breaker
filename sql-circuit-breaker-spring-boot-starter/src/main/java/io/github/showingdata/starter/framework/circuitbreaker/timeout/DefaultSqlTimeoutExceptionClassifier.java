package io.github.showingdata.starter.framework.circuitbreaker.timeout;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 默认 SQL 超时异常分类器：基于异常类名关键词与 JDBC SQLState 识别。
 *
 * <p>识别策略（任一命中即判定为超时/取消）：</p>
 * <ul>
 *   <li><b>异常类名关键词</b>：递归遍历 cause 链（深度上限 20，防自循环），类名不区分大小写匹配
 *       {@code SQLTimeoutException}（JDBC 标准）、{@code MySQLTimeoutException}、
 *       {@code QueryTimeoutException}、{@code QueryCanceledException}（PG）、
 *       {@code OperationCanceledException}（Oracle）等关键词即命中。
 *       类名匹配的好处：不依赖 driver 类是否在 classpath（用 {@code Class.forName}
 *       反射加载会引入类加载耦合），且能穿透 Spring / MyBatis / 连接池层层包装。</li>
 *   <li><b>JDBC SQLState</b>：{@code HYT00}（query timeout，JDBC 标准）、
 *       {@code HY008}（operation canceled，MySQL 用）、{@code 57014}（query canceled，PG）
 *       等已知超时相关 SQLState 即命中。</li>
 * </ul>
 *
 * <p><b>未覆盖的 driver</b>：达梦 DM7/DM8 抛出的超时异常类名、errorCode、SQLState
 * 须实测后补到下方集合；业务方也可声明自己的 {@link SqlTimeoutExceptionClassifier} Bean
 * 覆盖本实现，无需修改 SDK 代码。</p>
 *
 * @author chenjiang
 */
public class DefaultSqlTimeoutExceptionClassifier implements SqlTimeoutExceptionClassifier {

    private static final Logger log = LoggerFactory.getLogger(DefaultSqlTimeoutExceptionClassifier.class);

    /**
     * 异常类名关键词集合。匹配方式为 {@code lowerCaseClassName.contains(keyword)}。
     * 覆盖 JDBC 标准与主流 driver 的超时/取消异常命名，避免把通用
     * {@code java.util.concurrent.TimeoutException} 误判为 SQL 超时。
     */
    private static final List<String> TIMEOUT_CLASS_KEYWORDS = Arrays.asList(
            "sqltimeoutexception",        // JDBC 4.0 标准 java.sql.SQLTimeoutException / MySQLTimeoutException
            "querytimeoutexception",      // 部分 driver 的 query timeout 命名
            "querycanceledexception",     // PostgreSQL org.postgresql.util.PSQLException 包装
            "operationcanceledexception"  // Oracle
    );

    /**
     * 已知的超时/取消相关 SQLState。JDBC 规范的 {@code HYT00} 是最通用的查询超时标识，
     * 各 driver 实际抛出的 SQLState 不一定一致，故枚举主流 driver 已知值。
     */
    private static final Set<String> TIMEOUT_SQL_STATES = new HashSet<>(Arrays.asList(
            "HYT00",  // JDBC 标准 query timeout
            "HY008",  // operation canceled（MySQL 用）
            "57014"   // query canceled（PostgreSQL 用）
    ));

    /**
     * 异常链遍历深度上限，防止异常自循环（极少数 driver 包装 bug）导致死循环。
     */
    private static final int MAX_CAUSE_DEPTH = 20;

    @Override
    public boolean isTimeoutOrCancelled(Throwable t) {
        if (t == null) {
            return false;
        }
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth < MAX_CAUSE_DEPTH) {
            // 1. 异常类名关键词匹配
            String className = cur.getClass().getName();
            String normalizedClassName = className.toLowerCase(Locale.ROOT);
            for (String keyword : TIMEOUT_CLASS_KEYWORDS) {
                if (normalizedClassName.contains(keyword)) {
                    if (log.isDebugEnabled()) {
                        log.info("[SqlCircuitBreaker] timeout classifier matched by class name: {} (keyword={})",
                                className, keyword);
                    }
                    return true;
                }
            }
            // 2. SQLException 的 SQLState 匹配
            if (cur instanceof SQLException) {
                SQLException se = (SQLException) cur;
                String sqlState = se.getSQLState();
                if (sqlState != null && TIMEOUT_SQL_STATES.contains(sqlState)) {
                    if (log.isDebugEnabled()) {
                        log.info("[SqlCircuitBreaker] timeout classifier matched by SQLState: {} (class={})",
                                sqlState, className);
                    }
                    return true;
                }
            }
            cur = cur.getCause();
            depth++;
        }
        return false;
    }
}
