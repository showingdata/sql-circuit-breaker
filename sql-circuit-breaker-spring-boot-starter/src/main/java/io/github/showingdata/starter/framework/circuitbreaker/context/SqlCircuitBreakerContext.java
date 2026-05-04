package io.github.showingdata.starter.framework.circuitbreaker.context;

import io.github.showingdata.starter.framework.circuitbreaker.config.SqlCircuitBreakerConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * @author chenjiang
 */
public class SqlCircuitBreakerContext {

    private static final ThreadLocal<SqlCircuitBreakerConfig> CTX = new ThreadLocal<>();

    /**
     * 为当前线程设置配置，优先级最高，执行完毕后务必在 finally 块中调用 clear()。
     * 设置时立即做值域校验，让业务方尽早发现配置错误。
     */
    public static void set(SqlCircuitBreakerConfig config) {
        if (config != null) {
            validate(config);
        }
        CTX.set(config);
    }

    /**
     * ThreadLocal 编程式配置值域校验，规则与全局配置、注解校验对齐：
     * - timeout：null 表示不覆盖，0 无意义（任何 SQL 都超时），正数合法
     * - circuitOpenMs：null 表示不覆盖，0 会导致熔断后立即重置，正数合法
     * - failureThreshold：null 表示不覆盖，0 会导致永远无法触发熔断，正数合法
     */
    private static void validate(SqlCircuitBreakerConfig config) {
        List<String> errors = new ArrayList<>();
        if (config.getSelectTimeoutMs() != null && config.getSelectTimeoutMs() <= 0)
            errors.add("selectTimeoutMs 必须 > 0，当前值：" + config.getSelectTimeoutMs());
        if (config.getInsertTimeoutMs() != null && config.getInsertTimeoutMs() <= 0)
            errors.add("insertTimeoutMs 必须 > 0，当前值：" + config.getInsertTimeoutMs());
        if (config.getUpdateTimeoutMs() != null && config.getUpdateTimeoutMs() <= 0)
            errors.add("updateTimeoutMs 必须 > 0，当前值：" + config.getUpdateTimeoutMs());
        if (config.getDeleteTimeoutMs() != null && config.getDeleteTimeoutMs() <= 0)
            errors.add("deleteTimeoutMs 必须 > 0，当前值：" + config.getDeleteTimeoutMs());
        if (config.getCircuitOpenMs() != null && config.getCircuitOpenMs() <= 0)
            errors.add("circuitOpenMs 必须 > 0，当前值：" + config.getCircuitOpenMs());
        if (config.getFailureThreshold() != null && config.getFailureThreshold() < 1)
            errors.add("failureThreshold 必须 >= 1，当前值：" + config.getFailureThreshold());
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(
                    "[SqlCircuitBreaker] ThreadLocal 配置不合法：\n  - " + String.join("\n  - ", errors));
        }
    }

    public static SqlCircuitBreakerConfig get() {
        return CTX.get();
    }

    /**
     * 清理 ThreadLocal，防止线程池场景下的内存泄漏
     */
    public static void clear() {
        CTX.remove();
    }

    /**
     * 快捷方法：一次性设置四种 SQL 类型的超时
     */
    public static void setTimeout(long selectMs, long insertMs, long updateMs, long deleteMs) {
        SqlCircuitBreakerConfig cfg = CTX.get();
        if (cfg == null) {
            cfg = new SqlCircuitBreakerConfig();
        }
        cfg.setSelectTimeoutMs(selectMs).setInsertTimeoutMs(insertMs).setUpdateTimeoutMs(updateMs).setDeleteTimeoutMs(deleteMs);
        set(cfg);
    }
    /**
     * 快捷方法：覆盖 SELECT 超时阈值
     */
    public static void setSelectTimeout(long selectMs) {
        SqlCircuitBreakerConfig cfg = CTX.get();
        if (cfg == null) {
            cfg = new SqlCircuitBreakerConfig();
        }
        cfg.setSelectTimeoutMs(selectMs);
        set(cfg);
    }

    /**
     * 快捷方法：覆盖 INSERT 超时阈值
     */
    public static void setInsertTimeout(long insertMs) {
        SqlCircuitBreakerConfig cfg = CTX.get();
        if (cfg == null) {
            cfg = new SqlCircuitBreakerConfig();
        }
        cfg.setInsertTimeoutMs(insertMs);
        set(cfg);
    }

    /**
     * 快捷方法：覆盖 UPDATE 超时阈值
     */
    public static void setUpdateTimeout(long updateMs) {
        SqlCircuitBreakerConfig cfg = CTX.get();
        if (cfg == null) {
            cfg = new SqlCircuitBreakerConfig();
        }
        cfg.setUpdateTimeoutMs(updateMs);
        set(cfg);
    }

    /**
     * 快捷方法：覆盖 DELETE 超时阈值
     */
    public static void setDeleteTimeout(long deleteMs) {
        SqlCircuitBreakerConfig cfg = CTX.get();
        if (cfg == null) {
            cfg = new SqlCircuitBreakerConfig();
        }
        cfg.setDeleteTimeoutMs(deleteMs);
        set(cfg);
    }

    /**
     * 快捷方法：覆盖熔断持续时长
     */
    public static void setCircuitOpenMs(long circuitOpenMs) {
        SqlCircuitBreakerConfig cfg = CTX.get();
        if (cfg == null) {
            cfg = new SqlCircuitBreakerConfig();
        }
        cfg.setCircuitOpenMs(circuitOpenMs);
        set(cfg);
    }

    /**
     * 快捷方法：覆盖熔断触发阈值
     */
    public static void setFailureThreshold(int failureThreshold) {
        SqlCircuitBreakerConfig cfg = CTX.get();
        if (cfg == null) {
            cfg = new SqlCircuitBreakerConfig();
        }
        cfg.setFailureThreshold(failureThreshold);
        set(cfg);
    }

    /**
     * 快捷方法：当前线程完全跳过熔断检测
     */
    public static void disableCircuitBreaker() {
        SqlCircuitBreakerConfig cfg = CTX.get();
        if (cfg == null) {
            cfg = new SqlCircuitBreakerConfig();
        }
        cfg.setDisableCircuitBreaker(true);
        set(cfg);
    }

    private SqlCircuitBreakerContext() {
    }
}
