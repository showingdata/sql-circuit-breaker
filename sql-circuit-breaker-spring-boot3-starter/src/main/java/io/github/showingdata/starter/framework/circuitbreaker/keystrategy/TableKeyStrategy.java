package io.github.showingdata.starter.framework.circuitbreaker.keystrategy;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import io.github.showingdata.starter.framework.circuitbreaker.SqlFingerprintUtils;
import org.apache.ibatis.mapping.SqlCommandType;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 表粒度策略：dsKey:sqlType:tableName。
 * <p>
 * 同一张表的所有 SQL 共享一个熔断器，表级故障时更快触发保护，避免指纹粒度下
 * "同表 N 条 SQL 各自计数、迟迟不熔断"的碎片化问题。
 * <p>
 * 表名通过正则提取，解析失败（派生表无具名表等）回退到 fingerprintHash，
 * 即降级为指纹粒度，无回归。结果小写化。
 * <p>
 * 正则前先通过 {@link io.github.showingdata.starter.framework.circuitbreaker.SqlFingerprintUtils#stripLiteralsAndComments}
 * 剥离字符串字面量和注释，防止字面量/注释内容中的关键字（如 'x from y'、/&#42; FROM dual &#42;/）被误识别为表名。
 * <p>
 * 缓存策略：按 msId + rawSql 组合缓存表名，容量硬上界 10_000 条（LRU 驱逐），
 * 防止按天/月分表等 rawSql 无限变体场景下内存缓慢增长。
 * <ul>
 *   <li>静态 SQL（#{} 参数化）：同 Mapper 方法的 rawSql 固定，命中缓存只解析一次。</li>
 *   <li>动态表名 SQL（${tableName} 替换）：同 Mapper 方法产生不同 rawSql，
 *       如 order_202607 与 order_202608，各自独立缓存，熔断隔离正确，
 *       不会误归到首次解析的表。</li>
 *   <li>动态 SQL（&lt;if&gt;/&lt;foreach&gt;）：rawSql 随分支变化，按需重算，不影响正确性。</li>
 * </ul>
 *
 * @author chenjiang
 */
public class TableKeyStrategy implements CircuitBreakerKeyStrategy {

    private static final Pattern FROM_PATTERN = Pattern.compile("\\bFROM\\s+([\\w.]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern INTO_PATTERN = Pattern.compile("\\bINTO\\s+([\\w.]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern UPDATE_PATTERN = Pattern.compile("\\bUPDATE\\s+([\\w.]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DELETE_FROM_PATTERN = Pattern.compile("\\bDELETE\\s+FROM\\s+([\\w.]+)", Pattern.CASE_INSENSITIVE);
    // 不带 FROM 的 DELETE：负向先行断言排除 FROM，避免误捕获 "DELETE FROM x" 中的 FROM
    private static final Pattern DELETE_PATTERN = Pattern.compile("\\bDELETE\\s+(?!FROM\\b)([\\w.]+)", Pattern.CASE_INSENSITIVE);

    /**
     * 组合缓存键的分隔符。msId 是 Java FQCN（仅 [a-zA-Z0-9._$]），不含此字符，
     * 因此 (msId, rawSql) → msId|rawSql 的映射是单射，无碰撞。
     */
    private static final String CACHE_KEY_SEP = "|";

    /**
     * 表名缓存硬上界（LRU 驱逐）。动态表名场景（按天/月分表 ${tableName}）rawSql 变体
     * 随时间无限增长，无上界会缓慢泄漏内存；超出后驱逐最久未访问条目，
     * 重新提取仅一次清理 + 正则开销，代价可接受。
     */
    private static final int TABLE_CACHE_MAX_SIZE = 10_000;

    /**
     * 表名缓存：msId + sep + rawSql → 表名（或 "" 表示解析失败走指纹回退）。
     * 组合键确保动态表名 SQL（${tableName}）各自独立缓存，不互相污染。
     * 容量硬上界 {@link #TABLE_CACHE_MAX_SIZE}（LRU），防止动态表名场景内存无限增长。
     * 解析失败用 "" 哨兵缓存，避免同一 (msId, rawSql) 反复触发正则。
     */
    private final Cache<String, String> tableCache = CacheBuilder.newBuilder()
            .maximumSize(TABLE_CACHE_MAX_SIZE)
            .build();

    @Override
    public String resolve(CircuitBreakerKeyContext ctx) {
        String dsKey = ctx.getDsKey() != null ? ctx.getDsKey() : "default";
        String rawSql = ctx.getBoundSql().getSql();
        SqlCommandType sqlType = ctx.getSqlCommandType();
        String cacheKey = ctx.getMappedStatement().getId() + CACHE_KEY_SEP + rawSql;
        String cached = tableCache.asMap().computeIfAbsent(cacheKey, k -> {
            String t = extractTable(rawSql, sqlType);
            return (t != null) ? t : "";  // "" 哨兵：解析失败也缓存，避免反复正则
        });
        String table = cached.isEmpty() ? null : cached;
        String thirdPart = (table != null) ? table : ctx.getFingerprintHash();
        return dsKey + ":" + sqlType.name() + ":" + thirdPart;
    }

    /**
     * 按 SQL 类型提取目标表名，返回小写表名；解析失败返回 null（由调用方回退到指纹）。
     * 正则前先剥离字符串字面量和注释，防止其内容中的关键字被误识别为表名。
     */
    private String extractTable(String sql, SqlCommandType type) {
        if (sql == null) {
            return null;
        }
        String cleaned = SqlFingerprintUtils.stripLiteralsAndComments(sql);
        switch (type) {
            case SELECT:
                return lower(firstGroup(FROM_PATTERN, cleaned));
            case INSERT:
                return lower(firstGroup(INTO_PATTERN, cleaned));
            case UPDATE:
                return lower(firstGroup(UPDATE_PATTERN, cleaned));
            case DELETE:
                String t = firstGroup(DELETE_FROM_PATTERN, cleaned);
                if (t == null) {
                    t = firstGroup(DELETE_PATTERN, cleaned);
                }
                return lower(t);
            default:
                return null;
        }
    }

    private String firstGroup(Pattern p, String sql) {
        Matcher m = p.matcher(sql);
        return m.find() ? m.group(1) : null;
    }

    private String lower(String s) {
        return s != null ? s.toLowerCase() : null;
    }
}