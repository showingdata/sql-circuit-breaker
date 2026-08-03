package io.github.showingdata.starter.framework.circuitbreaker.keystrategy;

import org.apache.ibatis.mapping.SqlCommandType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * DatasourceKeyStrategy 验证：key = dsKey:sqlType，最粗粒度。
 * ms/boundSql/fingerprint/fingerprintHash 此策略均不使用，传 null 即可。
 */
class DatasourceKeyStrategyTest {

    private final DatasourceKeyStrategy strategy = new DatasourceKeyStrategy();

    private static CircuitBreakerKeyContext ctx(String dsKey, SqlCommandType type) {
        return new CircuitBreakerKeyContext(null, null, type, dsKey, null, null);
    }

    @Test
    void dropsFingerprint() {
        assertEquals("ds1:SELECT", strategy.resolve(ctx("ds1", SqlCommandType.SELECT)));
        assertEquals("ds1:INSERT", strategy.resolve(ctx("ds1", SqlCommandType.INSERT)));
        assertEquals("ds1:UPDATE", strategy.resolve(ctx("ds1", SqlCommandType.UPDATE)));
        assertEquals("ds1:DELETE", strategy.resolve(ctx("ds1", SqlCommandType.DELETE)));
    }

    @Test
    void dsKey_null_usesDefault() {
        assertEquals("default:SELECT", strategy.resolve(ctx(null, SqlCommandType.SELECT)));
    }

    @Test
    void sameDsKeyAndType_shareCircuit() {
        // 同 dsKey + 同 sqlType → 同一 key，无论其他字段如何
        String k1 = strategy.resolve(ctx("ds1", SqlCommandType.SELECT));
        String k2 = strategy.resolve(ctx("ds1", SqlCommandType.SELECT));
        assertEquals(k1, k2);
    }
}
