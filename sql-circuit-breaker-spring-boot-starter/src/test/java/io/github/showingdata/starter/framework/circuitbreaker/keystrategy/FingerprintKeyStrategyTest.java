package io.github.showingdata.starter.framework.circuitbreaker.keystrategy;

import org.apache.ibatis.mapping.SqlCommandType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * FingerprintKeyStrategy 验证：key 格式与 dsKey null 处理。
 * 关键约束：与历史内联拼装逻辑逐字符一致，保证默认行为零变化。
 * ms/boundSql 此策略不使用，传 null 即可。
 */
class FingerprintKeyStrategyTest {

    private final FingerprintKeyStrategy strategy = new FingerprintKeyStrategy();

    private static CircuitBreakerKeyContext ctx(String dsKey, SqlCommandType type, String fingerprintHash) {
        // ms、boundSql 对该策略无用，传 null 避免无谓的 MyBatis 对象构造
        return new CircuitBreakerKeyContext(null, null, type, dsKey, "fp", fingerprintHash);
    }

    @Test
    void normalKey() {
        assertEquals("ds1:SELECT:abc123", strategy.resolve(ctx("ds1", SqlCommandType.SELECT, "abc123")));
        assertEquals("ds2:INSERT:def456", strategy.resolve(ctx("ds2", SqlCommandType.INSERT, "def456")));
        assertEquals("ds3:UPDATE:ghi789", strategy.resolve(ctx("ds3", SqlCommandType.UPDATE, "ghi789")));
        assertEquals("ds4:DELETE:jkl012", strategy.resolve(ctx("ds4", SqlCommandType.DELETE, "jkl012")));
    }

    @Test
    void dsKey_null_usesDefault() {
        assertEquals("default:SELECT:abc", strategy.resolve(ctx(null, SqlCommandType.SELECT, "abc")));
    }

    @Test
    void matchesLegacyInlineFormat() {
        // 等价于旧内联拼装：(dsKey != null ? dsKey : "default") + ":" + sqlType + ":" + fingerprintHash
        String dsKey = "ds1";
        SqlCommandType type = SqlCommandType.SELECT;
        String fingerprintHash = "abc123";
        String legacyInline = (dsKey != null ? dsKey : "default") + ":" + type.name() + ":" + fingerprintHash;
        assertEquals(legacyInline, strategy.resolve(ctx(dsKey, type, fingerprintHash)));
    }
}
