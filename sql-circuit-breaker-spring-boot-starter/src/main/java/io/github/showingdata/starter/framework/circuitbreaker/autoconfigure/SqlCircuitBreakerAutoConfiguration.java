package io.github.showingdata.starter.framework.circuitbreaker.autoconfigure;


import io.github.showingdata.starter.framework.circuitbreaker.banner.SqlCircuitBreakerBanner;
import io.github.showingdata.starter.framework.circuitbreaker.config.ConfigResolver;
import io.github.showingdata.starter.framework.circuitbreaker.config.SqlCircuitBreakerProperties;
import io.github.showingdata.starter.framework.circuitbreaker.datasource.DataSourceKeyResolver;
import io.github.showingdata.starter.framework.circuitbreaker.datasource.DefaultDataSourceKeyResolver;
import io.github.showingdata.starter.framework.circuitbreaker.interceptor.SqlCircuitBreakerInterceptor;
import io.github.showingdata.starter.framework.circuitbreaker.message.MessageCenterClient;
import io.github.showingdata.starter.framework.circuitbreaker.message.NoOpMessageCenterClient;
import io.github.showingdata.starter.framework.circuitbreaker.metrics.MicrometerCircuitBreakerMetrics;
import io.github.showingdata.starter.framework.circuitbreaker.metrics.NoOpCircuitBreakerMetrics;
import io.github.showingdata.starter.framework.circuitbreaker.metrics.SqlCircuitBreakerMetrics;
import io.github.showingdata.starter.framework.circuitbreaker.registry.CircuitBreakerRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author chenjiang
 * <p>
 * 更多操作详细参考
 * <a href="https://github.com/showingdata/sql-circuit-breaker"/>
 * <p>
 * SQL 熔断器自动装配类。
 * <p>
 * 激活条件：类路径存在 MyBatis {@code Interceptor}（原生 MyBatis 和 MyBatis-Plus 均包含）
 * 且配置了 {@code sql-circuit-breaker.enabled=true}。
 * </p>
 * <p>
 * 注册的 Bean：
 * <ul>
 *   <li>{@link CircuitBreakerRegistry}：熔断状态注册中心</li>
 *   <li>{@link ConfigResolver}：多优先级配置合并器</li>
 *   <li>{@link MessageCenterClient}：消息中心客户端，默认为空实现，业务方可覆盖</li>
 *   <li>{@link DataSourceKeyResolver}：数据源标识解析器，默认基于 Environment ID，业务方可覆盖</li>
 *   <li>{@link SqlCircuitBreakerMetrics}：指标上报，Micrometer 存在时自动启用真实实现</li>
 *   <li>{@link SqlCircuitBreakerInterceptor}：MyBatis 拦截器，MP 自动收集注册</li>
 * </ul>
 * </p>
 */
@Configuration
@EnableConfigurationProperties(SqlCircuitBreakerProperties.class)
@ConditionalOnClass(name = "org.apache.ibatis.plugin.Interceptor")
@ConditionalOnProperty(prefix = "sql-circuit-breaker", name = "enabled", havingValue = "true", matchIfMissing = false)
@AutoConfigureAfter(name = "org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration")
public class SqlCircuitBreakerAutoConfiguration {

    @Value("${spring.application.name:unknown}")
    private String applicationName;

    @Bean
    @ConditionalOnProperty(prefix = "sql-circuit-breaker", name = "banner-enabled", havingValue = "true", matchIfMissing = true)
    public ApplicationListener<ApplicationReadyEvent> sqlCircuitBreakerBannerPrinter() {
        return event -> SqlCircuitBreakerBanner.print(System.out);
    }

    @Bean
    @ConditionalOnMissingBean
    public CircuitBreakerRegistry circuitBreakerRegistry(SqlCircuitBreakerProperties props) {
        props.validate();
        return new CircuitBreakerRegistry(props);
    }

    @Bean
    @ConditionalOnMissingBean
    public ConfigResolver configResolver(SqlCircuitBreakerProperties props) {
        return new ConfigResolver(props);
    }

    /**
     * 默认消息中心客户端空实现，避免业务方未提供实现时启动失败。
     * 业务方可通过声明自己的 {@link MessageCenterClient} Bean 覆盖此默认实现。
     */
    @Bean
    @ConditionalOnMissingBean(MessageCenterClient.class)
    public MessageCenterClient messageCenterClient() {
        return new NoOpMessageCenterClient();
    }

    /**
     * 默认数据源标识解析器，基于 MyBatis Environment ID。
     * 业务方可通过声明自己的 {@link DataSourceKeyResolver} Bean 覆盖，适配 dynamic-datasource 等框架。
     */
    @Bean
    @ConditionalOnMissingBean(DataSourceKeyResolver.class)
    public DataSourceKeyResolver dataSourceKeyResolver() {
        return new DefaultDataSourceKeyResolver();
    }

    /**
     * Micrometer 指标实现：类路径存在 MeterRegistry 时注册。
     * 独立静态内部配置类确保 MeterRegistry 类不在 classpath 时不触发类加载失败。
     * 使用 ObjectProvider 在 Bean 创建时懒解析 MeterRegistry，避免 @ConditionalOnBean 注册顺序竞争。
     * MeterRegistry 不存在时降级为 NoOpCircuitBreakerMetrics，保证 SDK 无侵入运行。
     */
    @Configuration
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    static class MicrometerMetricsConfiguration {

        @Bean
        @ConditionalOnMissingBean(SqlCircuitBreakerMetrics.class)
        public SqlCircuitBreakerMetrics sqlCircuitBreakerMetrics(
                ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterRegistryProvider,
                CircuitBreakerRegistry circuitBreakerRegistry,
                SqlCircuitBreakerProperties props) {
            io.micrometer.core.instrument.MeterRegistry mr = meterRegistryProvider.getIfAvailable();
            if (mr != null) {
                return new MicrometerCircuitBreakerMetrics(mr, circuitBreakerRegistry, props.getMetrics().isIncludeMapperId());
            }
            return new NoOpCircuitBreakerMetrics();
        }
    }

    /**
     * 降级空操作实现：Micrometer 不在 classpath 时生效，保证 SDK 无侵入运行。
     */
    @Bean
    @ConditionalOnMissingBean(SqlCircuitBreakerMetrics.class)
    public SqlCircuitBreakerMetrics noOpCircuitBreakerMetrics() {
        return new NoOpCircuitBreakerMetrics();
    }

    @Bean
    @ConditionalOnMissingBean
    public SqlCircuitBreakerInterceptor sqlCircuitBreakerInterceptor(SqlCircuitBreakerProperties props,
                                                                     CircuitBreakerRegistry registry,
                                                                     MessageCenterClient messageCenterClient,
                                                                     ConfigResolver configResolver,
                                                                     DataSourceKeyResolver dataSourceKeyResolver,
                                                                     SqlCircuitBreakerMetrics metrics) {
        return new SqlCircuitBreakerInterceptor(props, registry, messageCenterClient, configResolver, applicationName, dataSourceKeyResolver, metrics);
    }
}
