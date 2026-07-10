package io.github.showingdata.starter.framework.circuitbreaker.autoconfigure;

import io.github.showingdata.starter.framework.circuitbreaker.interceptor.SqlCircuitBreakerInterceptor;
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
 * 此 BeanPostProcessor 作为安全网，在 SqlSessionFactory 初始化后强制注入拦截器。
 * <p>
 * 通过 {@code sql-circuit-breaker.auto-inject} 控制（默认：true）。
 */
final class SqlCircuitBreakerInterceptorInjector implements BeanPostProcessor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(SqlCircuitBreakerInterceptorInjector.class);

    private final ObjectProvider<SqlCircuitBreakerInterceptor> interceptorProvider;

    SqlCircuitBreakerInterceptorInjector(ObjectProvider<SqlCircuitBreakerInterceptor> interceptorProvider) {
        this.interceptorProvider = interceptorProvider;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof SqlSessionFactory)) {
            return bean;
        }

        SqlCircuitBreakerInterceptor interceptor = interceptorProvider.getIfAvailable();
        if (interceptor == null) {
            log.debug("[SqlCircuitBreaker] 未找到拦截器 Bean，跳过 SqlSessionFactory [{}]", beanName);
            return bean;
        }

        SqlSessionFactory factory = (SqlSessionFactory) bean;
        Configuration configuration = factory.getConfiguration();
        List<Interceptor> interceptors = configuration.getInterceptors();
        boolean alreadyRegistered = containsSqlCircuitBreakerInterceptor(interceptors, interceptor);
        log.info("[SqlCircuitBreaker] 检查 SqlSessionFactory [{}] 拦截器链 | environmentId={} | interceptorCount={} | registered={}",
                beanName, resolveEnvironmentId(configuration), interceptors.size(), alreadyRegistered);
        if (alreadyRegistered) {
            log.info("[SqlCircuitBreaker] 拦截器已存在于 SqlSessionFactory [{}]，跳过兜底注入", beanName);
            return bean;
        }

        configuration.addInterceptor(interceptor);
        log.warn("[SqlCircuitBreaker] 拦截器已通过兜底机制注入到 SqlSessionFactory [{}]（MyBatis 自动扫描可能失效，建议排查）", beanName);
        return bean;
    }

    /**
     * 检查拦截器链中是否已包含 SQL 熔断器拦截器
     */
    private boolean containsSqlCircuitBreakerInterceptor(List<Interceptor> interceptors,
                                                         SqlCircuitBreakerInterceptor interceptor) {
        for (Interceptor registered : interceptors) {
            if (registered == interceptor || registered instanceof SqlCircuitBreakerInterceptor) {
                return true;
            }
        }
        return false;
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
