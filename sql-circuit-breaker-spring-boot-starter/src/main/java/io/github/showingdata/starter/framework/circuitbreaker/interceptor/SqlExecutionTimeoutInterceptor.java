package io.github.showingdata.starter.framework.circuitbreaker.interceptor;

import io.github.showingdata.starter.framework.circuitbreaker.config.SqlCircuitBreakerProperties;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.EnumSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * SQL 执行超时中断拦截器（StatementHandler.prepare 层）。
 * <p>
 * 与 {@link SqlCircuitBreakerInterceptor}（Executor 层事后耗时判定）互补：本拦截器在 MyBatis
 * prepare 出真实 JDBC {@link Statement} 后、真正执行前调用 {@code Statement.setQueryTimeout(int 秒)}，
 * 让驱动/数据库侧在超时点真正中断在途 SQL——而非等它跑完再对比。
 * <p>
 * <b>为什么拦截 prepare：</b>单一入口同时覆盖 query / update / batch / queryCursor / Callable，
 * 适配 Simple / Reuse / Batch 三类 Executor；proceed() 的返回值就是即将执行的真实 Statement，
 * 而一级/二级缓存命中根本不经过 prepare，天然零开销。
 * <p>
 * <b>阈值语义（跟随最终熔断配置）：</b>Executor 层熔断器已按 ThreadLocal、方法注解、
 * 接口注解、全局配置解析出当前 SQL 的 {@code timeout-ms}，本拦截器直接复用该最终值。
 * 因此 {@code disableCircuitBreaker=true} 时不会设置硬超时，注解/ThreadLocal 覆盖也会同步影响
 * JDBC queryTimeout。设置 Statement 时仍会读取既有 queryTimeout 现值（MyBatis XML timeout /
 * default-statement-timeout / Spring 事务超时），只收紧、绝不放宽。
 * <p>
 * <b>容错：</b>get/setQueryTimeout 抛 {@link SQLException}（含部分驱动如达梦抛
 * {@code SQLFeatureNotSupportedException}）时 WARN 放行，绝不阻断 SQL 执行。
 *
 * @author chenjiang
 */
@Intercepts({@Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class})})
public class SqlExecutionTimeoutInterceptor implements Interceptor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(SqlExecutionTimeoutInterceptor.class);

    /**
     * 合法的 SQL 类型集合（不含 UNKNOWN/FLUSH）。
     */
    private static final Set<SqlCommandType> ALL_TARGET_TYPES = EnumSet.of(SqlCommandType.SELECT, SqlCommandType.INSERT, SqlCommandType.UPDATE, SqlCommandType.DELETE);

    private final SqlCircuitBreakerProperties props;

    /**
     * 参与硬超时的 SQL 类型集合。缺省（null/空）= 全部四种；
     * 可显式收窄，如 MySQL DML 行为未 POC 前只配 [SELECT]。
     */
    private final Set<SqlCommandType> targetTypes;

    public SqlExecutionTimeoutInterceptor(SqlCircuitBreakerProperties props) {
        this.props = props;
        SqlCircuitBreakerProperties.ExecutionTimeoutConfig cfg = props.getExecutionTimeout();
        this.targetTypes = parseTargetTypes(cfg == null ? null : cfg.getTypes());
    }

    private static Set<SqlCommandType> parseTargetTypes(List<String> types) {
        EnumSet<SqlCommandType> set = EnumSet.noneOf(SqlCommandType.class);
        if (types == null || types.isEmpty()) {
            set.addAll(ALL_TARGET_TYPES);
            return set;
        }
        for (String t : types) {
            try {
                SqlCommandType type = SqlCommandType.valueOf(t.trim().toUpperCase());
                if (type == SqlCommandType.UNKNOWN || type == SqlCommandType.FLUSH) {
                    throw new IllegalArgumentException("不支持 SQL 类型: " + t);
                }
                set.add(type);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(
                        "[SqlCircuitBreaker] sql-circuit-breaker.execution-timeout.types 含非法值：" + t
                                + "，合法值：SELECT/INSERT/UPDATE/DELETE", e);
            }
        }
        return set;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        SqlCircuitBreakerProperties.ExecutionTimeoutConfig cfg = props.getExecutionTimeout();
        if (cfg == null || !cfg.isEnabled()) {
            return invocation.proceed();
        }

        // 先 proceed 拿到真实 Statement，再设超时；prepare 失败（连接/语法）原样上抛，不干预
        Statement stmt = (Statement) invocation.proceed();
        if (stmt == null) {
            return null;
        }

        SqlExecutionTimeoutContext.Entry ctx = SqlExecutionTimeoutContext.get();
        if (ctx != null && matchesTargetType(ctx.getSqlType())) {
            applyQueryTimeout(stmt, ctx.getTimeoutMs());
        }
        return stmt;
    }

    /**
     * 当前 SQL 类型是否命中 execution-timeout.types。
     */
    private boolean matchesTargetType(SqlCommandType type) {
        return targetTypes.contains(type);
    }

    /**
     * 设置硬超时：最终 timeout-ms 向上取整到秒（最小 1s），与 Statement 现值取 min 只收紧不放宽。
     * 任何 SQLException（含驱动不支持 setQueryTimeout）→ WARN 放行，不阻断 SQL。
     */
    private void applyQueryTimeout(Statement stmt, long timeoutMs) {
        if (timeoutMs <= 0) {
            return;
        }
        int seconds = Math.max(1, (int) Math.ceil(timeoutMs / 1000.0));
        try {
            // 0 或负数 = 无超时；既有超时（XML timeout / default-statement-timeout / @Transactional timeout）
            // 若更紧则胜出——本配置是"硬上限"，只收紧、不放宽
            int current = stmt.getQueryTimeout();
            int target = (current > 0) ? Math.min(current, seconds) : seconds;
            if (target > 0 && target != current) {
                stmt.setQueryTimeout(target);
            }
        } catch (SQLException e) {
            log.warn("[SqlCircuitBreaker] execution-timeout 设置查询超时失败，跳过（不影响 SQL 执行）| timeoutMs={} | error={}",
                    timeoutMs, e.getMessage());
        }
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    @Override
    public void setProperties(Properties properties) {
    }

    @Override
    public int getOrder() {
        // 与熔断器一致取最低优先级：MyBatis 拦截器链最外层包装，prepare 时 proceed 先经内层拿到最终 Statement，
        // 设置的超时不会被内层覆盖。与熔断器目标层（Executor vs StatementHandler）不同，互不冲突。
        return Ordered.LOWEST_PRECEDENCE;
    }
}
