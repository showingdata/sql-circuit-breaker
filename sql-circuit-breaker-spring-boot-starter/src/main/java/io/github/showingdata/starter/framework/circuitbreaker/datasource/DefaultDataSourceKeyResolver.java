package io.github.showingdata.starter.framework.circuitbreaker.datasource;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;

/**
 * 默认数据源标识解析器，基于 MyBatis Environment ID。
 * 单数据源场景返回 "default"；多数据源场景需业务方为每个 SqlSessionFactory
 * 配置不同的 environment id，否则所有数据源共用 "default"，熔断状态无法隔离。
 * 若使用 dynamic-datasource 等运行时切换框架，需实现 {@link DataSourceKeyResolver}
 * 并注册为 Spring Bean 覆盖此默认实现。 详细您可以参考READMD.md
 *
 * <a href="https://github.com/showingdata/sql-circuit-breaker"/>
 *
 * @author chenjiang
 */
public class DefaultDataSourceKeyResolver implements DataSourceKeyResolver {

    @Override
    public String resolve(MappedStatement ms) {
        Environment env = ms.getConfiguration().getEnvironment();
        return env != null && env.getId() != null ? env.getId() : "default";
    }
}
