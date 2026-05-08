package io.github.showingdata.starter.framework.circuitbreaker.autoconfigure;


import io.github.showingdata.starter.framework.circuitbreaker.banner.SqlCircuitBreakerBanner;
import io.github.showingdata.starter.framework.circuitbreaker.config.ConfigResolver;
import io.github.showingdata.starter.framework.circuitbreaker.config.SqlCircuitBreakerProperties;
import io.github.showingdata.starter.framework.circuitbreaker.datasource.DataSourceKeyResolver;
import io.github.showingdata.starter.framework.circuitbreaker.datasource.DefaultDataSourceKeyResolver;
import io.github.showingdata.starter.framework.circuitbreaker.interceptor.SqlCircuitBreakerInterceptor;
import io.github.showingdata.starter.framework.circuitbreaker.message.MessageCenterClient;
import io.github.showingdata.starter.framework.circuitbreaker.message.NoOpMessageCenterClient;
import io.github.showingdata.starter.framework.circuitbreaker.registry.CircuitBreakerRegistry;
import org.springframework.beans.factory.annotation.Value;
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
 *   <li>{@link SqlCircuitBreakerInterceptor}：MyBatis 拦截器，MP 自动收集注册</li>
 * </ul>
 * </p>
 */
@Configuration
@EnableConfigurationProperties(SqlCircuitBreakerProperties.class)
@ConditionalOnClass(name = "org.apache.ibatis.plugin.Interceptor")
@ConditionalOnProperty(prefix = "sql-circuit-breaker", name = "enabled", havingValue = "true", matchIfMissing = false)
public class SqlCircuitBreakerAutoConfiguration {

    @Value("${spring.application.name:unknown}")
    private String applicationName;

    @Bean
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
     * SqlCircuitBreakerInterceptor 实现 Ordered 接口，通过 sql-circuit-breaker.interceptor-order 配置顺序。
     * Spring Boot 注入 Interceptor[] 时会按 Ordered 排序，值越小排在数组越后（MyBatis 后注册的在最外层最先执行）。
     */
    @Bean
    @ConditionalOnMissingBean
    public SqlCircuitBreakerInterceptor sqlCircuitBreakerInterceptor(SqlCircuitBreakerProperties props,
                                                                     CircuitBreakerRegistry registry,
                                                                     MessageCenterClient messageCenterClient,
                                                                     ConfigResolver configResolver,
                                                                     DataSourceKeyResolver dataSourceKeyResolver) {
        return new SqlCircuitBreakerInterceptor(props, registry, messageCenterClient, configResolver, applicationName, dataSourceKeyResolver);
    }
}
