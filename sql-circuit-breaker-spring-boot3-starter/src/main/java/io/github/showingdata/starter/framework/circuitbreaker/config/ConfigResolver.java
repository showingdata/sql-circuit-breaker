package io.github.showingdata.starter.framework.circuitbreaker.config;

import io.github.showingdata.starter.framework.circuitbreaker.annotation.SqlCircuitBreaker;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 配置解析器
 *
 * @author chenjiang
 */
public class ConfigResolver {

    private static final Logger log = LoggerFactory.getLogger(ConfigResolver.class);

    private final SqlCircuitBreakerProperties global;

    /**
     * 注解解析结果缓存：MappedStatement.id → (methodAnn, ifaceAnn)。
     * 注解是编译期静态的，解析一次后无需重复反射，显著降低高并发下的性能开销。
     */
    private final ConcurrentHashMap<String, AnnotationPair> annotationCache = new ConcurrentHashMap<>();

    public ConfigResolver(SqlCircuitBreakerProperties global) {
        this.global = global;
    }

    private static final class AnnotationPair {
        final SqlCircuitBreaker method;
        final SqlCircuitBreaker iface;

        AnnotationPair(SqlCircuitBreaker method, SqlCircuitBreaker iface) {
            this.method = method;
            this.iface = iface;
        }
    }

    /**
     * 按优先级解析配置：ThreadLocal > 方法注解 > 接口注解 > 全局配置（按 SQL 类型精确取值）。
     * <p>
     * ThreadLocal 快照由调用方在拦截器入口一次性读取传入，本方法内部不再访问 ThreadLocal，
     * 保证整次 intercept 调用看到的 ThreadLocal 配置一致，且不受调用方提前 clear() 影响。
     */
    public ResolvedConfig resolve(MappedStatement ms, SqlCommandType sqlType, SqlCircuitBreakerConfig tl) {
        // 注解首次解析时一并校验，结果缓存后后续请求直接命中缓存，无需重复校验。
        // validateAnnotation 抛 IllegalArgumentException（unchecked），可在 lambda 内直接抛出。
        AnnotationPair pair = annotationCache.computeIfAbsent(ms.getId(), k -> {
            SqlCircuitBreaker methodAnn = resolveMethodAnnotation(ms);
            SqlCircuitBreaker ifaceAnn = resolveInterfaceAnnotation(ms);
            if (methodAnn != null) {
                validateAnnotation(methodAnn, "方法级", k);
            }
            if (ifaceAnn != null) {
                validateAnnotation(ifaceAnn, "接口级", k);
            }
            return new AnnotationPair(methodAnn, ifaceAnn);
        });

        return ResolvedConfig.builder()
                .timeout(mergeTimeout(sqlType, tl, pair.method, pair.iface))
                .circuitOpenMs(mergeCircuitOpenMs(sqlType, tl, pair.method, pair.iface))
                .failureThreshold(mergeFailureThreshold(sqlType, tl, pair.method, pair.iface))
                .disableCircuitBreaker(mergeDisable(tl, pair.method, pair.iface))
                .build();
    }

    /**
     * 注解值域合理性校验：
     * - timeoutMs：-1 表示继承，0 无意义（任何 SQL 都超时），其余正数合法
     * - circuitOpenMs：-1 表示继承，0 会导致熔断后立即重置失去保护，其余正数合法
     * - failureThreshold：-1 表示继承，0 会导致永远无法触发熔断，其余正数合法
     */
    private void validateAnnotation(SqlCircuitBreaker ann, String level, String mapperId) {
        List<String> errors = new ArrayList<>();
        if (ann.timeoutMs() == 0) {
            errors.add("timeoutMs 不能为 0，会导致所有 SQL 立即超时");
        }
        if (ann.circuitOpenMs() == 0) {
            errors.add("circuitOpenMs 不能为 0，会导致熔断后立即重置，保护失效");
        }
        if (ann.failureThreshold() == 0) {
            errors.add("failureThreshold 不能为 0，永远无法触发熔断");
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(
                    "[SqlCircuitBreaker] " + level + "注解配置不合法，mapper=" + mapperId + "：\n  - "
                            + String.join("\n  - ", errors));
        }
    }

    private SqlCircuitBreaker resolveMethodAnnotation(MappedStatement ms) {
        String id = ms.getId();
        try {
            int lastDot = id.lastIndexOf('.');
            if (lastDot <= 0) {
                return null;
            }
            Class<?> mapperClass = loadClass(id.substring(0, lastDot));
            String methodName = id.substring(lastDot + 1);
            List<Method> candidates = new ArrayList<>();
            for (Method method : mapperClass.getMethods()) {
                if (method.getName().equals(methodName)) {
                    candidates.add(method);
                }
            }
            if (candidates.isEmpty()) {
                return null;
            }
            if (candidates.size() == 1) {
                return candidates.get(0).getAnnotation(SqlCircuitBreaker.class);
            }
            SqlCircuitBreaker found = null;
            for (Method method : candidates) {
                SqlCircuitBreaker ann = method.getAnnotation(SqlCircuitBreaker.class);
                if (ann != null) {
                    if (found == null) {
                        found = ann;
                    } else {
                        log.warn("[SqlCircuitBreaker] 多个重载方法均有 SqlCircuitBreaker，取第一个匹配，id={}", id);
                    }
                }
            }
            return found;
        } catch (Exception e) {
            log.debug("[SqlCircuitBreaker] resolve method annotation failed, id={}", id);
        }
        return null;
    }

    private SqlCircuitBreaker resolveInterfaceAnnotation(MappedStatement ms) {
        String id = ms.getId();
        try {
            int lastDot = id.lastIndexOf('.');
            if (lastDot <= 0) {
                return null;
            }
            Class<?> mapperClass = loadClass(id.substring(0, lastDot));
            return mapperClass.getAnnotation(SqlCircuitBreaker.class);
        } catch (Exception e) {
            log.debug("[SqlCircuitBreaker] resolve interface annotation failed, id={}", id);
        }
        return null;
    }

    private Class<?> loadClass(String className) throws ClassNotFoundException {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl != null) {
            return cl.loadClass(className);
        }
        return Class.forName(className);
    }

    private long mergeTimeout(SqlCommandType type, SqlCircuitBreakerConfig tl, SqlCircuitBreaker method, SqlCircuitBreaker iface) {
        if (tl != null && tl.getTimeoutMs() != null && tl.getTimeoutMs() > 0) {
            return tl.getTimeoutMs();
        }
        if (method != null && method.timeoutMs() >= 0) {
            return method.timeoutMs();
        }
        if (iface != null && iface.timeoutMs() >= 0) {
            return iface.timeoutMs();
        }
        return global.getConfigByType(type).getTimeoutMs();
    }

    private long mergeCircuitOpenMs(SqlCommandType type, SqlCircuitBreakerConfig tl, SqlCircuitBreaker method, SqlCircuitBreaker iface) {
        if (tl != null && tl.getCircuitOpenMs() != null && tl.getCircuitOpenMs() > 0) {
            return tl.getCircuitOpenMs();
        }
        if (method != null && method.circuitOpenMs() >= 0) {
            return method.circuitOpenMs();
        }
        if (iface != null && iface.circuitOpenMs() >= 0) {
            return iface.circuitOpenMs();
        }
        return global.getConfigByType(type).getCircuitOpenMs();
    }

    private int mergeFailureThreshold(SqlCommandType type, SqlCircuitBreakerConfig tl, SqlCircuitBreaker method, SqlCircuitBreaker iface) {
        if (tl != null && tl.getFailureThreshold() != null && tl.getFailureThreshold() > 0) {
            return tl.getFailureThreshold();
        }
        if (method != null && method.failureThreshold() >= 0) {
            return method.failureThreshold();
        }
        if (iface != null && iface.failureThreshold() >= 0) {
            return iface.failureThreshold();
        }
        return global.getConfigByType(type).getFailureThreshold();
    }

    private boolean mergeDisable(SqlCircuitBreakerConfig tl, SqlCircuitBreaker method, SqlCircuitBreaker iface) {
        if (tl != null && Boolean.TRUE.equals(tl.getDisableCircuitBreaker())) {
            return true;
        }
        if (method != null && method.disableCircuitBreaker()) {
            return true;
        }
        if (iface != null && iface.disableCircuitBreaker()) {
            return true;
        }
        return false;
    }
}
