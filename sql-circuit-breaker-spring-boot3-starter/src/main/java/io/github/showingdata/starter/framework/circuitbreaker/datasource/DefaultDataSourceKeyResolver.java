package io.github.showingdata.starter.framework.circuitbreaker.datasource;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 默认数据源标识解析器，基于 MyBatis Environment ID。
 * 单数据源场景返回 "default"；多数据源场景需业务方为每个 SqlSessionFactory
 * 配置不同的 environment id，否则所有数据源共用 "default"，熔断状态无法隔离。
 * 若使用 dynamic-datasource 等运行时切换框架，需实现 {@link DataSourceKeyResolver}
 * 并注册为 Spring Bean 覆盖此默认实现。详细可参考 README.md。
 *
 * <a href="https://github.com/showingdata/sql-circuit-breaker"/>
 *
 * @author chenjiang
 */
public class DefaultDataSourceKeyResolver implements DataSourceKeyResolver {

    /**
     * MyBatis / MyBatis-Plus 在未显式配置 environment id 时使用的框架默认值。
     * 这些值对业务方无区分意义，统一归一化为 "default"，避免日志中出现类名噪音。
     */
    private static final Set<String> FRAMEWORK_DEFAULT_IDS = new HashSet<>(Arrays.asList(
            "MybatisSqlSessionFactoryBean",
            "SqlSessionFactoryBean"
    ));

    @Override
    public String resolve(MappedStatement ms) {
        Environment env = ms.getConfiguration().getEnvironment();
        if (env == null || env.getId() == null || FRAMEWORK_DEFAULT_IDS.contains(env.getId())) {
            return "default";
        }
        return env.getId();
    }
}
