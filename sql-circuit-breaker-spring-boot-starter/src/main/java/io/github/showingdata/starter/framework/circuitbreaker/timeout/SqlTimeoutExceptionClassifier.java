package io.github.showingdata.starter.framework.circuitbreaker.timeout;

/**
 * SQL 超时异常分类器：判断一个异常是否由 driver/数据库侧执行超时或取消引发。
 *
 * <p>当 {@link io.github.showingdata.starter.framework.circuitbreaker.interceptor.SqlExecutionTimeoutInterceptor}
 * 通过 JDBC {@code Statement.setQueryTimeout} 设置的硬超时被触发时，driver 会抛出特定类型的
 * {@link SQLException}（或其子类 / 包装类）。本接口供
 * {@link io.github.showingdata.starter.framework.circuitbreaker.interceptor.SqlCircuitBreakerInterceptor}
 * 在 catch 到异常时识别「这是硬超时事件」，据此调用 {@code handleTimeout} 计入熔断失败计数，
 * 让 driver 中断与熔断状态机联动起来——避免「SQL 被 driver 中断了，熔断器却收不到任何信号」
 * 的逻辑漏洞。</p>
 *
 * <p>业务方如有自定义 driver（如某些国产数据库）异常识别需求，可声明自己的 Bean 覆盖默认实现。
 * 默认实现 {@link DefaultSqlTimeoutExceptionClassifier} 覆盖主流 driver（MySQL/PG/Oracle）
 * 与 JDBC 标准超时语义；达梦 DM7/DM8 的超时异常类名/SQLState 待 POC 实测后补充，
 * 业务方也可自行声明 Bean 适配。</p>
 *
 * @author chenjiang
 */
public interface SqlTimeoutExceptionClassifier {

    /**
     * 判断异常（或其 cause 链上任意一层）是否为 SQL 执行超时 / 取消。
     *
     * @param t 业务执行 SQL 时抛出的异常，可为 null
     * @return true 表示该异常源自 driver/数据库侧超时或取消，应计入熔断失败
     */
    boolean isTimeoutOrCancelled(Throwable t);
}
