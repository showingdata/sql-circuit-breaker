package io.github.showingdata.starter.framework.circuitbreaker.datasource;

import org.apache.ibatis.mapping.MappedStatement;

/**
 * 数据源标识解析器扩展接口。
 * 熔断 Key 中包含数据源标识，用于多数据源场景下隔离各数据源的熔断状态。
 * 默认实现为 {@link DefaultDataSourceKeyResolver}，基于 MyBatis Environment ID。
 * 业务方可声明自己的 Bean 覆盖默认实现，适配任意数据源框架（如 dynamic-datasource 等）。
 *
 * @author chenjiang
 */
public interface DataSourceKeyResolver {

    /**
     * 返回当前执行 SQL 所属的数据源标识，用于构建熔断 Key。
     * 不同数据源必须返回不同的标识，否则熔断状态无法隔离。
     *
     * @param ms 当前执行的 MappedStatement
     * @return 数据源标识，不能为 null
     */
    String resolve(MappedStatement ms);
}
