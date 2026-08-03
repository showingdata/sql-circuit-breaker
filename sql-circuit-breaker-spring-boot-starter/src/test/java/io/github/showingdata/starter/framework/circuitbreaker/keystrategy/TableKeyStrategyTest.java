package io.github.showingdata.starter.framework.circuitbreaker.keystrategy;

import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TableKeyStrategy 表名提取的正则边界覆盖。
 * 通过真实 MyBatis Configuration 构造 MappedStatement/BoundSql，不引入 Mockito。
 */
class TableKeyStrategyTest {

    private final TableKeyStrategy strategy = new TableKeyStrategy();

    private static CircuitBreakerKeyContext ctx(String sql, SqlCommandType type, String dsKey, String fingerprintHash) {
        Configuration config = new Configuration();
        // MappedStatement.Builder 签名：(Configuration, id, SqlSource, SqlCommandType)
        MappedStatement ms = new MappedStatement.Builder(
                config, "com.example.Mapper." + type.name().toLowerCase(),
                new StaticSqlSource(config, sql), type).build();
        BoundSql boundSql = new BoundSql(config, sql, new ArrayList<>(), null);
        return new CircuitBreakerKeyContext(ms, boundSql, type, dsKey, sql, fingerprintHash);
    }

    @Test
    void select_simple() {
        assertEquals("ds1:SELECT:orders",
                strategy.resolve(ctx("SELECT * FROM orders WHERE id = ?", SqlCommandType.SELECT, "ds1", "hash1")));
    }

    @Test
    void select_caseInsensitive() {
        assertEquals("ds1:SELECT:orders",
                strategy.resolve(ctx("select col1, col2 From ORDERS where id=?", SqlCommandType.SELECT, "ds1", "h")));
    }

    @Test
    void select_schemaQualified() {
        assertEquals("ds:SELECT:db.orders",
                strategy.resolve(ctx("SELECT * FROM db.orders WHERE id=?", SqlCommandType.SELECT, "ds", "h")));
    }

    @Test
    void select_withAlias() {
        assertEquals("ds:SELECT:orders",
                strategy.resolve(ctx("SELECT o.id FROM orders o WHERE o.id=?", SqlCommandType.SELECT, "ds", "h")));
    }

    @Test
    void select_join_returnsFirstTable() {
        // JOIN 场景取首个 FROM 后的表
        assertEquals("ds:SELECT:orders",
                strategy.resolve(ctx("SELECT * FROM orders o JOIN users u ON o.uid=u.id", SqlCommandType.SELECT, "ds", "h")));
    }

    @Test
    void select_subquery_extractsInnerTable() {
        // 子查询含具名表：正则跨到内层 FROM，提取 orders（合理：SQL 确实接触 orders 表）
        assertEquals("ds:SELECT:orders",
                strategy.resolve(ctx("SELECT * FROM (SELECT id FROM orders) t", SqlCommandType.SELECT, "ds", "h")));
    }

    @Test
    void select_derivedTableNoInnerTable_fallsBackToFingerprint() {
        // 派生表且子查询无具名表：FROM 后是 '(' 且无其他 FROM，正则无匹配 → 回退 fingerprintHash
        assertEquals("ds:SELECT:fallbackHash",
                strategy.resolve(ctx("SELECT * FROM (SELECT 1) t", SqlCommandType.SELECT, "ds", "fallbackHash")));
    }

    @Test
    void select_cte_extractsUnderlyingTable() {
        // CTE：正则跨过 CTE 定义捕获底层物理表 orders（比 CTE 别名 t 更有用）
        assertEquals("ds:SELECT:orders",
                strategy.resolve(ctx("WITH t AS (SELECT id FROM orders) SELECT * FROM t", SqlCommandType.SELECT, "ds", "h")));
    }

    @Test
    void insert_into() {
        assertEquals("ds:INSERT:orders",
                strategy.resolve(ctx("INSERT INTO orders (id, name) VALUES (?, ?)", SqlCommandType.INSERT, "ds", "h")));
    }

    @Test
    void update_simple() {
        assertEquals("ds:UPDATE:orders",
                strategy.resolve(ctx("UPDATE orders SET name=? WHERE id=?", SqlCommandType.UPDATE, "ds", "h")));
    }

    @Test
    void delete_from() {
        assertEquals("ds:DELETE:orders",
                strategy.resolve(ctx("DELETE FROM orders WHERE id=?", SqlCommandType.DELETE, "ds", "h")));
    }

    @Test
    void delete_withoutFrom() {
        // 不带 FROM 的 DELETE（部分方言）：负向断言排除 FROM 后捕获表名
        assertEquals("ds:DELETE:orders",
                strategy.resolve(ctx("DELETE orders WHERE id=?", SqlCommandType.DELETE, "ds", "h")));
    }

    @Test
    void dsKey_null_fallsBackToDefault() {
        String key = strategy.resolve(ctx("SELECT * FROM orders", SqlCommandType.SELECT, null, "h"));
        assertTrue(key.startsWith("default:SELECT:"), "key 应以 default:SELECT: 开头，实际：" + key);
    }

    @Test
    void dynamicTableName_extractsDistinctTables() {
        // ${tableName} 字符串替换：同 Mapper 方法产生不同 rawSql，应各自独立提取表名
        // 修复前 bug：按 msId 单键缓存，第二次会命中并返回首次的 order_202607，导致熔断隔离错位
        CircuitBreakerKeyContext c1 = ctx("SELECT * FROM order_202607 WHERE id=?", SqlCommandType.SELECT, "ds", "h1");
        CircuitBreakerKeyContext c2 = ctx("SELECT * FROM order_202608 WHERE id=?", SqlCommandType.SELECT, "ds", "h2");
        assertEquals("ds:SELECT:order_202607", strategy.resolve(c1));
        assertEquals("ds:SELECT:order_202608", strategy.resolve(c2));
    }

    @Test
    void sameMsIdSameRawSql_returnsConsistentResult() {
        // 同 msId + 同 rawSql：两次调用结果一致（#{} 参数化 SQL 走缓存命中，rawSql 固定）
        CircuitBreakerKeyContext c1 = ctx("SELECT * FROM orders WHERE id=?", SqlCommandType.SELECT, "ds", "h1");
        CircuitBreakerKeyContext c2 = ctx("SELECT * FROM orders WHERE id=?", SqlCommandType.SELECT, "ds", "h2");
        assertEquals("ds:SELECT:orders", strategy.resolve(c1));
        assertEquals("ds:SELECT:orders", strategy.resolve(c2));
    }
}
