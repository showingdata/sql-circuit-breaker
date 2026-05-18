package io.github.showingdata.starter.framework.circuitbreaker.config;

import lombok.Data;
import org.apache.ibatis.mapping.SqlCommandType;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL 熔断器配置类，按 SQL 类型（SELECT / INSERT / UPDATE / DELETE）独立配置。
 * <p>
 * 配置示例：
 * <pre>
 * sql-circuit-breaker:
 *   enabled: true
 *   select:
 *     timeout-ms: 3000
 *     failure-threshold: 3
 *     circuit-open-ms: 60000
 *     cache-max-size: 10000
 *     cache-expire-after-access-minutes: 30
 *   insert:
 *     timeout-ms: 5000
 *     failure-threshold: 1
 *     circuit-open-ms: 30000
 *     cache-max-size: 5000
 *     cache-expire-after-access-minutes: 30
 *   update:
 *     timeout-ms: 5000
 *     failure-threshold: 1
 *     circuit-open-ms: 30000
 *     cache-max-size: 5000
 *     cache-expire-after-access-minutes: 30
 *   delete:
 *     timeout-ms: 3000
 *     failure-threshold: 1
 *     circuit-open-ms: 30000
 *     cache-max-size: 5000
 *     cache-expire-after-access-minutes: 30
 * </pre>
 *
 * @author chenjiang
 */
@Data
@ConfigurationProperties(prefix = "sql-circuit-breaker")
public class SqlCircuitBreakerProperties {

    private boolean enabled = false;

    private SqlTypeConfig select;
    private SqlTypeConfig insert;
    private SqlTypeConfig update;
    private SqlTypeConfig delete;

    /**
     * 指标采集相关配置。无需配置即使用默认值（含 mapper_id 标签，最大细粒度）。
     */
    private MetricsConfig metrics = new MetricsConfig();

    /**
     * 根据 SQL 类型获取对应的配置块。
     */
    public SqlTypeConfig getConfigByType(SqlCommandType type) {
        switch (type) {
            case SELECT: return select;
            case INSERT: return insert;
            case UPDATE: return update;
            case DELETE: return delete;
            default: throw new IllegalArgumentException("[SqlCircuitBreaker] 不支持的 SQL 类型: " + type);
        }
    }

    /**
     * 启动期校验：四种 SQL 类型的配置块均为必填，各字段均须合法。
     * 无全局兜底，业务方必须显式配置每种类型，确保行为明确可预期。
     */
    public void validate() {
        validateType("select", select);
        validateType("insert", insert);
        validateType("update", update);
        validateType("delete", delete);
    }

    private void validateType(String name, SqlTypeConfig config) {
        String prefix = "sql-circuit-breaker." + name;
        if (config == null) {
            throw new IllegalStateException(
                    "[SqlCircuitBreaker] " + prefix + " 配置块未配置，请在 application.yml 中补充");
        }
        List<String> errors = new ArrayList<>();
        if (config.getTimeoutMs() == null || config.getTimeoutMs() <= 0) {
            errors.add(prefix + ".timeout-ms 必须 > 0，当前值：" + config.getTimeoutMs());
        }
        if (config.getFailureThreshold() == null || config.getFailureThreshold() < 1) {
            errors.add(prefix + ".failure-threshold 必须 >= 1，当前值：" + config.getFailureThreshold());
        }
        if (config.getCircuitOpenMs() == null || config.getCircuitOpenMs() <= 0) {
            errors.add(prefix + ".circuit-open-ms 必须 > 0，当前值：" + config.getCircuitOpenMs());
        }
        if (config.getCacheMaxSize() == null || config.getCacheMaxSize() < 1) {
            errors.add(prefix + ".cache-max-size 必须 >= 1，当前值：" + config.getCacheMaxSize());
        }
        if (config.getCacheExpireAfterAccessMinutes() == null || config.getCacheExpireAfterAccessMinutes() < 1) {
            errors.add(prefix + ".cache-expire-after-access-minutes 必须 >= 1，当前值：" + config.getCacheExpireAfterAccessMinutes());
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException(
                    "[SqlCircuitBreaker] 配置不合法：\n  - " + String.join("\n  - ", errors));
        }
    }

    /**
     * 指标配置：控制 Micrometer 指标的标签粒度，用于平衡可观测性细度与时间序列基数。
     */
    @Data
    public static class MetricsConfig {

        /**
         * timeout / open / fast.fail 三项指标是否带 mapper_id 标签。
         * <p>
         * - true（默认）：保留 mapper_id 标签，Grafana 可按 Mapper 维度排序定位问题，
         *   但时间序列数 = Mapper 方法数 × SQL 类型数 × 3，大型系统（数百 Mapper × 多副本 × 多服务）
         *   可能撑爆 Prometheus 后端的时间序列预算。<br>
         * - false：去掉 mapper_id 标签，三项指标仅按 sql_type 聚合，时间序列数固定为 12，
         *   定位具体 Mapper 改用日志中的 mapper 字段（`[SqlCircuitBreaker] 熔断开启 | mapper=xxx`）。
         * <p>
         * intercept.total 和 open.count 不受此开关影响（前者本就无 mapper_id 标签，后者是 Gauge）。
         */
        private boolean includeMapperId = true;
    }

    /**
     * 单种 SQL 类型的配置块。
     */
    @Data
    public static class SqlTypeConfig {

        /**
         * 超时阈值（毫秒），SQL 执行超过此时间视为超时，累计计入失败次数。
         */
        private Long timeoutMs;

        /**
         * 连续超时触发熔断的次数阈值。
         * SELECT 建议 3（慢查询有偶发性）；DML 建议 1（持锁影响大，快速止损）。
         */
        private Integer failureThreshold;

        /**
         * 熔断持续时长（毫秒），到期后自动重置为 CLOSED。建议 60000（60s）。
         */
        private Long circuitOpenMs;

        /**
         * 该 SQL 类型熔断状态的 Guava Cache 最大条目数，超出时按 LRU 驱逐。
         */
        private Long cacheMaxSize;

        /**
         * 缓存条目无访问后的驱逐时间（分钟），防止长期不用的 SQL 驻留内存。
         */
        private Long cacheExpireAfterAccessMinutes;
    }
}
