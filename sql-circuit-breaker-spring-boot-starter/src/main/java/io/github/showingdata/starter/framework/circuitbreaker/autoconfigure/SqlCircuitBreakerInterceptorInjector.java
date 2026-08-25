package io.github.showingdata.starter.framework.circuitbreaker.autoconfigure;

import io.github.showingdata.starter.framework.circuitbreaker.interceptor.SqlCircuitBreakerInterceptor;
import io.github.showingdata.starter.framework.circuitbreaker.interceptor.SqlExecutionTimeoutInterceptor;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;

import java.util.List;

/**
 * SQL 熔断器拦截器兜底注入器。
 * <p>
 * 正常情况下，MyBatis 会自动扫描 Spring 容器中的 {@link Interceptor} Bean 并注册到 SqlSessionFactory。
 * 但部分应用手动构建 SqlSessionFactory 或使用自定义配置，可能绕过了这个自动扫描机制。
 * 此 BeanPostProcessor 作为安全网，在 SqlSessionFactory 初始化后强制注入 {@link SqlCircuitBreakerInterceptor}
 * 与 {@link SqlExecutionTimeoutInterceptor}（后者默认关闭，Bean 不存在时经 ObjectProvider 天然跳过）。
 * <p>
 * 通过 {@code sql-circuit-breaker.auto-inject} 控制（默认：true）。
 */
public final class SqlCircuitBreakerInterceptorInjector implements BeanPostProcessor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(SqlCircuitBreakerInterceptorInjector.class);

    private final ObjectProvider<SqlCircuitBreakerInterceptor> interceptorProvider;
    private final ObjectProvider<SqlExecutionTimeoutInterceptor> timeoutInterceptorProvider;

    SqlCircuitBreakerInterceptorInjector(ObjectProvider<SqlCircuitBreakerInterceptor> interceptorProvider,
                                         ObjectProvider<SqlExecutionTimeoutInterceptor> timeoutInterceptorProvider) {
        this.interceptorProvider = interceptorProvider;
        this.timeoutInterceptorProvider = timeoutInterceptorProvider;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof SqlSessionFactory)) {
            return bean;
        }
        SqlSessionFactory factory = (SqlSessionFactory) bean;
        ensureRegistered(factory, beanName, interceptorProvider.getIfAvailable(), SqlCircuitBreakerInterceptor.class, "SqlCircuitBreakerInterceptor");
        ensureRegistered(factory, beanName, timeoutInterceptorProvider.getIfAvailable(), SqlExecutionTimeoutInterceptor.class, "SqlExecutionTimeoutInterceptor");
        return bean;
    }

    private void ensureRegistered(SqlSessionFactory factory, String beanName, Interceptor interceptor, Class<? extends Interceptor> type, String name) {
        if (interceptor == null) {
            log.debug("[SqlCircuitBreaker] 未找到拦截器 Bean（{}），跳过 SqlSessionFactory [{}] 的兜底注入", name, beanName);
            return;
        }
        Configuration configuration = factory.getConfiguration();
        List<Interceptor> interceptors = configuration.getInterceptors();
        boolean alreadyRegistered = interceptors.stream().anyMatch(i -> i == interceptor || i.getClass().equals(type));
        log.info("[SqlCircuitBreaker] 检查 SqlSessionFactory [{}] 拦截器链 | environmentId={} | interceptorCount={} | registered={}", beanName, resolveEnvironmentId(configuration), interceptors.size(), alreadyRegistered);
        if (alreadyRegistered) {
            log.info("[SqlCircuitBreaker] 拦截器（{}）已存在于 SqlSessionFactory [{}]，跳过兜底注入", name, beanName);
            return;
        }
        configuration.addInterceptor(interceptor);
        log.warn("[SqlCircuitBreaker] 拦截器（{}）已通过兜底机制注入到 SqlSessionFactory [{}]（MyBatis 自动扫描可能失效，建议排查）", name, beanName);
    }

    private String resolveEnvironmentId(Configuration configuration) {
        if (configuration.getEnvironment() == null) {
            return "unknown";
        }
        return configuration.getEnvironment().getId();
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
