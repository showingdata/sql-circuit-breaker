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
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

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

    private static final Logger log = LoggerFactory.getLogger(SqlCircuitBreakerAutoConfiguration.class);

    @Value("${spring.application.name:unknown}")
    private String applicationName;

    @Bean
    @ConditionalOnProperty(prefix = "sql-circuit-breaker", name = "banner-enabled", havingValue = "true", matchIfMissing = true)
    public ApplicationListener<ApplicationReadyEvent> sqlCircuitBreakerBannerPrinter() {
        return event -> SqlCircuitBreakerBanner.print(System.out);
    }

    /**
     * 拦截器兜底注入器。
     * <p>
     * <b>为什么需要这个 Bean：</b>
     * <br>
     * 正常情况下，MyBatis/MyBatis-Plus 会自动扫描 Spring 容器中所有 {@link org.apache.ibatis.plugin.Interceptor}
     * 类型的 Bean，并注册到 {@code SqlSessionFactory} 的拦截器链中。但在以下场景中，自动扫描可能失效：
     * <ul>
     *   <li>业务方手动构建 {@code SqlSessionFactory}，绕过了 MyBatis Boot 的自动配置</li>
     *   <li>使用多数据源框架（如 dynamic-datasource）时，部分数据源的拦截器未被正确收集</li>
     *   <li>自定义 MyBatis 配置类覆盖了默认的拦截器注册逻辑</li>
     * </ul>
     * <p>
     * 此 {@link BeanPostProcessor} 作为安全网，在所有 {@code SqlSessionFactory} 初始化后检查拦截器链，
     * 如果发现 {@link SqlCircuitBreakerInterceptor} 未注册，则强制注入，确保熔断器在所有场景下都能生效。
     * <p>
     * 实际案例：某业务系统使用 MyBatis-Plus + 自定义多数据源配置，自动扫描失效导致熔断器不生效，
     * 通过此兜底机制成功注入。
     * <p>
     * 通过 {@code sql-circuit-breaker.auto-inject=false} 可关闭此兜底机制（默认开启）。
     */
    @Bean
    @ConditionalOnProperty(prefix = "sql-circuit-breaker", name = "auto-inject", havingValue = "true", matchIfMissing = true)
    public static BeanPostProcessor sqlCircuitBreakerInterceptorInjector(ObjectProvider<SqlCircuitBreakerInterceptor> interceptorProvider) {
        return new SqlCircuitBreakerInterceptorInjector(interceptorProvider);
    }

    /**
     * 启动期注解校验：所有单例就绪后，遍历每个 {@link SqlSessionFactory} 的全部 MappedStatement，
     * 复用 {@link ConfigResolver#prevalidate} 对 {@code @SqlCircuitBreaker} 注解做值域校验并预热缓存。
     * <p>
     * 把注解校验从「首次调用该 Mapper 时」提前到启动期 fail-fast，与 yml 的 {@code props.validate()} 对齐。
     * 注解非法抛出的 {@link IllegalArgumentException} 故意不拦截 → 直接中断启动；
     * 仅对「枚举 MappedStatement」这一步做兜底：MyBatis 版本差异等导致枚举失败时记 WARN 并退回懒校验，
     * 不因校验器自身问题阻塞启动。
     * <p>
     * 默认关闭，需显式配置 {@code sql-circuit-breaker.validate-annotations-on-startup=true} 开启。
     * 关闭时仍有运行期懒校验（首次调用该 Mapper 时校验）兜底，不影响功能正确性。
     */
    @Bean
    @ConditionalOnProperty(prefix = "sql-circuit-breaker", name = "validate-annotations-on-startup", havingValue = "true", matchIfMissing = false)
    public SmartInitializingSingleton sqlCircuitBreakerAnnotationValidator(ObjectProvider<SqlSessionFactory> sqlSessionFactories, ConfigResolver configResolver) {
        return () -> {
            Set<String> seen = new HashSet<>();
            int warmed = 0;
            for (SqlSessionFactory factory : sqlSessionFactories) {
                Collection<?> statements;
                try {
                    statements = factory.getConfiguration().getMappedStatements();
                } catch (Throwable t) {
                    log.warn("[SqlCircuitBreaker] 枚举 MappedStatement 失败，跳过该 SqlSessionFactory 的启动期注解校验，退回运行期懒校验", t);
                    continue;
                }
                for (Object obj : statements) {
                    // StrictMap 对短 key 冲突会塞入 Ambiguity 占位对象（非 MappedStatement），跳过
                    if (!(obj instanceof MappedStatement)) {
                        continue;
                    }
                    MappedStatement ms = (MappedStatement) obj;
                    // 同一条语句以全 id + 短 id 各存一份，按 id 去重
                    if (!seen.add(ms.getId())) {
                        continue;
                    }
                    try {
                        configResolver.prevalidate(ms);
                        warmed++;
                    } catch (IllegalArgumentException e) {
                        // 注解值域非法 → fail-fast 中断启动（这正是本功能的目的）
                        throw e;
                    } catch (Throwable t) {
                        // 类加载等非校验类意外：不阻塞启动，退回运行期懒校验
                        log.warn("[SqlCircuitBreaker] 预校验 {} 时遇到非校验类异常，跳过，退回运行期懒校验", ms.getId(), t);
                    }
                }
            }
            log.info("[SqlCircuitBreaker] 启动期注解校验完成，共校验并预热 {} 条 MappedStatement", warmed);
        };
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
