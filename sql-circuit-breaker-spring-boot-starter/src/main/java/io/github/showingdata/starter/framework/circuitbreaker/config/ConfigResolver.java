package io.github.showingdata.starter.framework.circuitbreaker.config;

import io.github.showingdata.starter.framework.circuitbreaker.annotation.SqlCircuitBreaker;
import io.github.showingdata.starter.framework.circuitbreaker.context.SqlCircuitBreakerContext;
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
     * ThreadLocal 动态配置在缓存命中后仍按原优先级覆盖，不影响灵活性。
     */
    private final ConcurrentHashMap<String, AnnotationPair> annotationCache = new ConcurrentHashMap<>();

    public ConfigResolver(SqlCircuitBreakerProperties global) {
        this.global = global;
    }

    /**
     * 缓存的注解解析结果 holder。
     */
    private static final class AnnotationPair {
        final SqlCircuitBreaker method;
        final SqlCircuitBreaker iface;

        AnnotationPair(SqlCircuitBreaker method, SqlCircuitBreaker iface) {
            this.method = method;
            this.iface = iface;
        }
    }

    /**
     * 按优先级解析配置：ThreadLocal > 方法注解 > 接口注解 > 全局配置。
     * sqlType 已知，直接解析出对应超时值，返回的 ResolvedConfig.timeout 无需再传 sqlType。
     */
    public ResolvedConfig resolve(MappedStatement ms, SqlCommandType sqlType) {
        SqlCircuitBreakerConfig tl = SqlCircuitBreakerContext.get();

        // 从缓存获取注解解析结果（首次会触发反射解析），避免每次 SQL 执行都重复反射
        AnnotationPair pair = annotationCache.computeIfAbsent(ms.getId(), k -> {
            SqlCircuitBreaker methodAnn = resolveMethodAnnotation(ms);
            SqlCircuitBreaker ifaceAnn = resolveInterfaceAnnotation(ms);
            return new AnnotationPair(methodAnn, ifaceAnn);
        });

        // 注解值域校验：注解在运行时按需加载，无法在启动期统一检查，首次解析时校验并快速失败
        if (pair.method != null) {
            validateAnnotation(pair.method, "方法级", ms.getId());
        }
        if (pair.iface != null) {
            validateAnnotation(pair.iface, "接口级", ms.getId());
        }

        return ResolvedConfig.builder()
                .timeout(mergeTimeout(sqlType, tl, pair.method, pair.iface))
                .circuitOpenMs(mergeCircuitOpenMs(tl, pair.method, pair.iface))
                .failureThreshold(mergeFailureThreshold(sqlType, tl, pair.method, pair.iface))
                .disableCircuitBreaker(mergeDisable(tl, pair.method, pair.iface))
                .build();
    }

    /**
     * 注解值域合理性校验，规则与全局配置 validate() 对齐：
     * - timeout：-1 表示继承，0 无意义（任何 SQL 都超时），其余正数合法
     * - circuitOpenMs：-1 表示继承，0 会导致熔断后立即重置失去保护，其余正数合法
     * - failureThreshold：-1 表示继承，0 会导致永远无法触发熔断，其余正数合法
     */
    private void validateAnnotation(SqlCircuitBreaker ann, String level, String mapperId) {
        List<String> errors = new ArrayList<>();
        if (ann.selectTimeout() == 0) errors.add("selectTimeout 不能为 0，会导致所有 SELECT 立即超时");
        if (ann.insertTimeout() == 0) errors.add("insertTimeout 不能为 0，会导致所有 INSERT 立即超时");
        if (ann.updateTimeout() == 0) errors.add("updateTimeout 不能为 0，会导致所有 UPDATE 立即超时");
        if (ann.deleteTimeout() == 0) errors.add("deleteTimeout 不能为 0，会导致所有 DELETE 立即超时");
        if (ann.circuitOpenMs() == 0) errors.add("circuitOpenMs 不能为 0，会导致熔断后立即重置，保护失效");
        if (ann.selectFailureThreshold() == 0) errors.add("selectFailureThreshold 不能为 0，永远无法触发熔断");
        if (ann.dmlFailureThreshold() == 0) errors.add("dmlFailureThreshold 不能为 0，永远无法触发熔断");
        if (ann.failureThreshold() == 0) errors.add("failureThreshold 不能为 0，永远无法触发熔断");
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
            // 无重载：直接返回
            if (candidates.size() == 1) {
                return candidates.get(0).getAnnotation(SqlCircuitBreaker.class);
            }
            // 有重载：优先找带注解的方法，若多个带注解则取第一个并 warn
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
        if (tl != null) {
            Long v = tl.getTimeout(type);
            if (v != null && v >= 0) return v;
        }
        if (method != null) {
            long v = annotationTimeout(method, type);
            if (v >= 0) return v;
        }
        if (iface != null) {
            long v = annotationTimeout(iface, type);
            if (v >= 0) return v;
        }
        return global.getTimeout(type);
    }

    private long annotationTimeout(SqlCircuitBreaker ann, SqlCommandType type) {
        switch (type) {
            case SELECT:
                return ann.selectTimeout();
            case INSERT:
                return ann.insertTimeout();
            case UPDATE:
                return ann.updateTimeout();
            case DELETE:
                return ann.deleteTimeout();
            default:
                return -1;
        }
    }

    private long mergeCircuitOpenMs(SqlCircuitBreakerConfig tl, SqlCircuitBreaker method, SqlCircuitBreaker iface) {
        if (tl != null && tl.getCircuitOpenMs() != null && tl.getCircuitOpenMs() >= 0) {
            return tl.getCircuitOpenMs();
        }
        if (method != null && method.circuitOpenMs() >= 0) {
            return method.circuitOpenMs();
        }
        if (iface != null && iface.circuitOpenMs() >= 0) {
            return iface.circuitOpenMs();
        }
        return global.getCircuitOpenMs();
    }

    private int mergeFailureThreshold(SqlCommandType sqlType, SqlCircuitBreakerConfig tl, SqlCircuitBreaker method, SqlCircuitBreaker iface) {
        boolean isSelect = sqlType == SqlCommandType.SELECT;
        // ThreadLocal：先找特定类型，再找通用 fallback
        if (tl != null) {
            Integer specific = isSelect ? tl.getSelectFailureThreshold() : tl.getDmlFailureThreshold();
            if (specific != null && specific > 0) return specific;
            if (tl.getFailureThreshold() != null && tl.getFailureThreshold() > 0) return tl.getFailureThreshold();
        }
        // 方法注解：先找特定类型，再找通用 fallback
        if (method != null) {
            int specific = isSelect ? method.selectFailureThreshold() : method.dmlFailureThreshold();
            if (specific > 0) return specific;
            if (method.failureThreshold() > 0) return method.failureThreshold();
        }
        // 接口注解：先找特定类型，再找通用 fallback
        if (iface != null) {
            int specific = isSelect ? iface.selectFailureThreshold() : iface.dmlFailureThreshold();
            if (specific > 0) return specific;
            if (iface.failureThreshold() > 0) return iface.failureThreshold();
        }
        return global.getFailureThreshold(sqlType);
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
