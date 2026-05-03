package io.github.showingdata.starter.framework.circuitbreaker.config;

import lombok.Data;
import org.apache.ibatis.mapping.SqlCommandType;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * @author chenjiang
 *
 * <p>
 * SQL 断路器 配置类
 * <p>
 * ┌─────────────────────────────────┬────────┬─────────────────────────────────────────────────────────────────────────────┐
 * │              参数               │ 建议值 │                                    理由                                      │
 * ├─────────────────────────────────┼────────┼─────────────────────────────────────────────────────────────────────────────┤
 * │ select-timeout-ms               │ 10s    │ OLTP 查询正常 < 1s，复杂报表留到 5s，10s 抓的是真正的全表扫描 / 索引缺失        │
 * ├─────────────────────────────────┼────────┼─────────────────────────────────────────────────────────────────────────────┤
 * │ insert/update/delete-timeout-ms │ 5s     │ DML 持锁，拖得越久其他事务越堵，应比 SELECT 更快熔断                           │
 * ├─────────────────────────────────┼────────┼─────────────────────────────────────────────────────────────────────────────┤
 * │ circuit-open-ms                 │ 60s    │ 给 DBA 足够时间发现并处理问题，也给连接池时间回收                              │
 * ├─────────────────────────────────┼────────┼─────────────────────────────────────────────────────────────────────────────┤
 * │ select-failure-threshold        │ 3      │ 慢查询偶发概率高，连续 3 次才算系统性问题，避免 GC/冷缓存偶发误熔断              │
 * ├─────────────────────────────────┼────────┼─────────────────────────────────────────────────────────────────────────────┤
 * │ dml-failure-threshold           │ 1      │ DML 持锁，1 次超时即熔断可快速止损                                             │
 * └─────────────────────────────────┴────────┴─────────────────────────────────────────────────────────────────────────────┘
 */
@Data
@ConfigurationProperties(prefix = "sql-circuit-breaker")
public class SqlCircuitBreakerProperties {

    private boolean enabled = false;
    // 以下字段均为必填，无默认值，未配置时启动报错
    private Long selectTimeoutMs;
    private Long insertTimeoutMs;
    private Long updateTimeoutMs;
    private Long deleteTimeoutMs;
    private Long circuitOpenMs;
    private Integer selectFailureThreshold;
    private Integer dmlFailureThreshold;
    /**
     * MyBatis 拦截器顺序，值越小越先注册（后注册的在最外层先执行）。默认最低优先级，确保熔断器在链最外层最先执行。
     */
    private int interceptorOrder = org.springframework.core.Ordered.LOWEST_PRECEDENCE;

    /**
     * 在 AutoConfiguration 创建 Bean 时调用，启动期校验必填项及合理性，快速失败并给出明确提示。
     * 第一阶段：检查必填（null）；第二阶段：检查值域合理性，避免运行期出现"任何 SQL 都超时"等静默错误。
     */
    public void validate() {
        // 第一阶段：必填项检查
        List<String> missing = getStrings();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("[SqlCircuitBreaker] 以下配置项未配置，请在 application.yml 中补充 sql-circuit-breaker.*：" + missing);
        }
        // 第二阶段：值域合理性检查
        List<String> invalid = new ArrayList<>();
        // timeout 必须 > 0，= 0 会导致任何 SQL 都超时
        if (selectTimeoutMs <= 0) {
            invalid.add("select-timeout-ms 必须 > 0，当前值：" + selectTimeoutMs);
        }
        if (insertTimeoutMs <= 0) {
            invalid.add("insert-timeout-ms 必须 > 0，当前值：" + insertTimeoutMs);
        }
        if (updateTimeoutMs <= 0) {
            invalid.add("update-timeout-ms 必须 > 0，当前值：" + updateTimeoutMs);
        }
        if (deleteTimeoutMs <= 0) {
            invalid.add("delete-timeout-ms 必须 > 0，当前值：" + deleteTimeoutMs);
        }
        // circuitOpenMs 必须 > 0，负数或 0 会导致熔断后立即重置，失去保护意义
        if (circuitOpenMs <= 0) {
            invalid.add("circuit-open-ms 必须 > 0，当前值：" + circuitOpenMs);
        }
        // failureThreshold 必须 >= 1，= 0 会导致永远无法触发熔断
        if (selectFailureThreshold < 1) {
            invalid.add("select-failure-threshold 必须 >= 1，当前值：" + selectFailureThreshold);
        }
        if (dmlFailureThreshold < 1) {
            invalid.add("dml-failure-threshold 必须 >= 1，当前值：" + dmlFailureThreshold);
        }
        if (!invalid.isEmpty()) {
            throw new IllegalStateException("[SqlCircuitBreaker] 以下配置项值不合法，请检查 sql-circuit-breaker.*：\n  - " + String.join("\n  - ", invalid));
        }
    }

    private List<String> getStrings() {
        List<String> missing = new ArrayList<>();
        if (selectTimeoutMs == null) {
            missing.add("select-timeout-ms");
        }
        if (insertTimeoutMs == null) {
            missing.add("insert-timeout-ms");
        }
        if (updateTimeoutMs == null) {
            missing.add("update-timeout-ms");
        }
        if (deleteTimeoutMs == null) {
            missing.add("delete-timeout-ms");
        }
        if (circuitOpenMs == null) {
            missing.add("circuit-open-ms");
        }
        if (selectFailureThreshold == null) {
            missing.add("select-failure-threshold");
        }
        if (dmlFailureThreshold == null) {
            missing.add("dml-failure-threshold");
        }
        return missing;
    }

    public int getFailureThreshold(SqlCommandType type) {
        if (type == SqlCommandType.SELECT) {
            return selectFailureThreshold;
        }
        // INSERT / UPDATE / DELETE
        return dmlFailureThreshold;
    }

    public long getTimeout(SqlCommandType type) {
        switch (type) {
            case INSERT:
                return insertTimeoutMs;
            case UPDATE:
                return updateTimeoutMs;
            case DELETE:
                return deleteTimeoutMs;
            case SELECT:
            default:
                return selectTimeoutMs;
        }
    }
}
