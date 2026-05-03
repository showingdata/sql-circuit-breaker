# SQL 熔断插件设计方案

> 基于 MyBatis Plus Interceptor 的 SQL 超时熔断 SDK

---

## 1. 背景与目标

### 1.1 问题

业务系统中某些慢 SQL（全表扫描、缺少索引、锁等待）在高并发下会导致：

- 数据库连接池耗尽，引发雪崩
- 上游调用线程大量阻塞，接口超时
- 相同慢 SQL 被重复发送，持续打垮 DB

### 1.2 目标

在 MyBatis Plus（MP）层面对所有 CRUD SQL 进行拦截，提供：

| 能力 | 说明 |
|---|---|
| 超时检测 | 按 SQL 类型（SELECT/INSERT/UPDATE/DELETE）独立配置超时阈值 |
| 自动熔断 | 超时后自动进入熔断状态，熔断期间相同 SQL 本地快速失败，不发送到 DB |
| 多级配置 | 全局配置 → Mapper 接口注解 → Mapper 方法注解 → ThreadLocal 编程式覆盖 |
| 快速失败 | 熔断期间抛出指定业务异常，记录结构化错误日志 |
| 消息通知 | 直接引入消息中心 starter，熔断事件推送（系统/方法/SQL/时间/熔断时长） |

---

## 2. 整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                        业务系统                                  │
│  Mapper 接口 / 方法                                              │
│  @XWFrameworkSqlCircuitBreaker(selectTimeout=5000)                        │
│  XWFrameworkSqlCircuitBreakerContext.set(...)  ← ThreadLocal 编程式覆盖 │
└───────────────────────────┬─────────────────────────────────────┘
                            │ MyBatis Plus 执行 SQL
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│           XWFrameworkSqlCircuitBreakerInterceptor                │
│  1. 解析 SQL 类型（SELECT/INSERT/UPDATE/DELETE）                 │
│  2. 生成 SQL 指纹（去参数值，保留 SQL 结构）                     │
│  3. 查询配置（ThreadLocal > 方法注解 > 接口注解 > 全局配置）     │
│  4. 查询熔断状态（CircuitBreakerRegistry）                       │
│     ├── OPEN  → 快速失败，抛 SqlCircuitBreakerException         │
│     └── CLOSED → 正常执行，计时                                  │
│  5. 执行超时 → 更新熔断状态 → 发布熔断事件                      │
└───────────────────────────┬─────────────────────────────────────┘
                            │
              ┌─────────────┴──────────────┐
              ▼                            ▼
  CircuitBreakerRegistry          MessageCenterClient（直接注入）
  （熔断状态存储：内存）            （你们的消息中心 starter Bean）
  SQL指纹 → CircuitBreakerState
  状态：CLOSED / OPEN
```

---

## 3. 核心概念

### 3.1 SQL 指纹（Fingerprint）

熔断的匹配单位是 **SQL 指纹**，而非完整 SQL（参数值不同，结构相同的 SQL 视为同一类）。

生成规则：
1. 将所有参数占位符（`?` 或 `#{xxx}`）替换为 `?`
2. 合并连续空白为单个空格
3. 统一转小写
4. 取 MD5 或直接用规范化后的字符串作为 Key

示例：

```sql
-- 原始 SQL（两次调用，参数不同）
SELECT * FROM order WHERE user_id = 123 AND status = 1
SELECT * FROM order WHERE user_id = 456 AND status = 2

-- 指纹（相同）
select * from order where user_id = ? and status = ?
```

这样设计的好处是：只要这类查询结构有问题（比如缺索引导致全表扫），不管传什么参数都会慢，熔断一次就把整类 SQL
都保护起来了，而不是每个参数值单独计数

**熔断 Key 的设计:**

这里为了避免长SQL 熔断 Key 的设计为`sql_type:fingerprint`，对这条对已提取的 SQL 指纹取 MD5 例`SELECT:a3f2c1...` 

### 3.2 熔断状态机

```
            连续超时 >= failureThreshold
  CLOSED ──────────────────────────────→ OPEN
    ↑                                      │
    └──────── circuitOpenMs 到期自动重置 ───┘
```

| 状态 | 行为 |
|---|---|
| `CLOSED` | 正常执行，记录执行时间；超时则累加计数，达到阈值则转 OPEN |
| `OPEN` | 拒绝所有请求，直接快速失败；circuitOpenMs 到期后自动重置为 CLOSED |

熔断状态存储结构（`CircuitBreakerState`）：

```java
public class CircuitBreakerState {
    private final String sqlFingerprint;
    private volatile State state;          // CLOSED / OPEN
    private volatile long openTimestamp;   // 进入 OPEN 的时间
    private volatile long circuitOpenMs;   // 本次熔断时长（毫秒）
    private volatile int  consecutiveFail; // 连续超时次数
    private volatile long lastActiveTime;  // 最近活跃时间，供 evictIdle 清理使用
}
```

### 3.3 配置优先级（从高到低）

```
ThreadLocal 编程式 > 方法级注解 > 接口级注解 > 全局配置文件
```

## 4. 模块设计

### 4.1 注解定义

```java
package com.xw.tmp.starter.framework.circuitbreaker.annotation;

import java.lang.annotation.*;

@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface XWFrameworkSqlCircuitBreaker {

    /**
     * SELECT 超时阈值（毫秒），-1 表示继承上层配置
     */
    long selectTimeout() default -1;

    /**
     * INSERT 超时阈值（毫秒），-1 表示继承上层配置
     */
    long insertTimeout() default -1;

    /**
     * UPDATE 超时阈值（毫秒），-1 表示继承上层配置
     */
    long updateTimeout() default -1;

    /**
     * DELETE 超时阈值（毫秒），-1 表示继承上层配置
     */
    long deleteTimeout() default -1;

    /**
     * 熔断持续时长（毫秒），-1 表示继承上层配置
     */
    long circuitOpenMs() default -1;

    /**
     * 触发熔断所需的连续超时次数，-1 表示继承上层配置
     */
    int failureThreshold() default -1;

    /**
     * 是否禁用熔断（只执行 SQL，不做熔断检测）
     */
    boolean disableCircuitBreaker() default false;
}
```

### 4.2 全局配置（application.yml）

```yaml
sql-circuit-breaker:
    enabled: true
    # SELECT 超时阈值（毫秒） 参考建议:10s 抓的是真正的全表扫描/索引缺失 
    select-timeout-ms: 10000
    # INSERT UPDATE DELETE 时阈值（毫秒） 参考建议:5s 拖得越久其他事务越堵，应比 SELECT 更快熔断
    insert-timeout-ms: 5000
    update-timeout-ms: 5000
    delete-timeout-ms: 5000
    # 熔断持续时长（毫秒）：超时触发熔断后进入 OPEN 状态，拒绝所有请求，
    # 等待 circuit-open-ms 到期后自动重置为 CLOSED。参考建议: 60000
    # 设长一点是给 DBA 处理问题留时间，也让连接池有机会回收
    circuit-open-ms: 60000
    # 可选：按 SQL 类型细分熔断阈值  慢查询偶发概率高，容忍连续 3 次再熔断 参考建议:3  
    select-failure-threshold: 3
    # DML持锁危害大，1 次超时即熔断快速止损 参考建议:1 
    dml-failure-threshold: 1
```

对应配置类：

```java
@Data
@ConfigurationProperties(prefix = "sql-circuit-breaker")
public class XWFrameworkSqlCircuitBreakerProperties {

    private boolean enabled = false;
    // 以下字段均为必填，无默认值，未配置时启动报错
    private Long selectTimeoutMs;
    private Long insertTimeoutMs;
    private Long updateTimeoutMs;
    private Long deleteTimeoutMs;
    private Long circuitOpenMs;
    private Integer selectFailureThreshold;
    private Integer dmlFailureThreshold;
    private int interceptorOrder = org.springframework.core.Ordered.LOWEST_PRECEDENCE;

    public void validate() {
        List<String> missing = new ArrayList<>();
        if (selectTimeoutMs == null)        missing.add("select-timeout-ms");
        if (insertTimeoutMs == null)        missing.add("insert-timeout-ms");
        if (updateTimeoutMs == null)        missing.add("update-timeout-ms");
        if (deleteTimeoutMs == null)        missing.add("delete-timeout-ms");
        if (circuitOpenMs == null)          missing.add("circuit-open-ms");
        if (selectFailureThreshold == null) missing.add("select-failure-threshold");
        if (dmlFailureThreshold == null)    missing.add("dml-failure-threshold");
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                "[SqlCircuitBreaker] 以下配置项未配置，请在 application.yml 中补充 sql-circuit-breaker.*：" + missing);
        }
    }

    public int getFailureThreshold(SqlCommandType type) {
        return type == SqlCommandType.SELECT ? selectFailureThreshold : dmlFailureThreshold;
    }

    public long getTimeout(SqlCommandType type) {
        switch (type) {
            case INSERT: return insertTimeoutMs;
            case UPDATE: return updateTimeoutMs;
            case DELETE: return deleteTimeoutMs;
            case SELECT:
            default:     return selectTimeoutMs;
        }
    }
}
```

### 4.3 ThreadLocal 编程式工具

```java
public class XWFrameworkSqlCircuitBreakerContext {

    private static final ThreadLocal<XWFrameworkSqlCircuitBreakerConfig> CTX = new ThreadLocal<>();

    /**
     * 为当前线程设置配置（优先级最高，执行完毕后务必在 finally 块中调用 clear()）。
     * 设置时立即做值域校验，让业务方尽早发现配置错误。
     */
    public static void set(XWFrameworkSqlCircuitBreakerConfig config) {
        if (config != null) { validate(config); }
        CTX.set(config);
    }

    public static XWFrameworkSqlCircuitBreakerConfig get() {
        return CTX.get();
    }

    /** 清理 ThreadLocal，防止线程池复用场景下内存泄漏 */
    public static void clear() {
        CTX.remove();
    }

    // ---- 快捷方法 ----

    /** 一次性设置四种 SQL 类型的超时 */
    public static void setTimeout(long selectMs, long insertMs, long updateMs, long deleteMs) {
        XWFrameworkSqlCircuitBreakerConfig cfg = CTX.get();
        if (cfg == null) { cfg = new XWFrameworkSqlCircuitBreakerConfig(); }
        cfg.setSelectTimeoutMs(selectMs).setInsertTimeoutMs(insertMs)
           .setUpdateTimeoutMs(updateMs).setDeleteTimeoutMs(deleteMs);
        set(cfg);
    }

    /** 覆盖熔断触发阈值 */
    public static void setFailureThreshold(int failureThreshold) {
        XWFrameworkSqlCircuitBreakerConfig cfg = CTX.get();
        if (cfg == null) { cfg = new XWFrameworkSqlCircuitBreakerConfig(); }
        cfg.setFailureThreshold(failureThreshold);
        set(cfg);
    }

    /** 当前线程完全跳过熔断检测 */
    public static void disableCircuitBreaker() {
        XWFrameworkSqlCircuitBreakerConfig cfg = CTX.get();
        if (cfg == null) { cfg = new XWFrameworkSqlCircuitBreakerConfig(); }
        cfg.setDisableCircuitBreaker(true);
        set(cfg);  // 走 set() 而非直接 CTX.set()，保证校验路径一致
    }
}

@Data
@Accessors(chain = true)
public class XWFrameworkSqlCircuitBreakerConfig {
    private Long selectTimeoutMs;
    private Long insertTimeoutMs;
    private Long updateTimeoutMs;
    private Long deleteTimeoutMs;
    private Long circuitOpenMs;
    private Integer failureThreshold;
    private Boolean disableCircuitBreaker;
}
```

使用示例：

```java
try {
    XWFrameworkSqlCircuitBreakerContext.setTimeout(5000, 3000, 3000, 3000);
    orderMapper.complexQuery(params);
} finally {
    XWFrameworkSqlCircuitBreakerContext.clear();  // 必须清理
}
```

### 4.4 MyBatis Plus 拦截器

```java
@Intercepts({
    @Signature(type = Executor.class, method = "query",
               args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
    @Signature(type = Executor.class, method = "query",
               args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class,
                       CacheKey.class, BoundSql.class}),
    @Signature(type = Executor.class, method = "update",
               args = {MappedStatement.class, Object.class})
})
public class XWFrameworkSqlCircuitBreakerInterceptor implements Interceptor, Ordered, DisposableBean {

    private final XWFrameworkSqlCircuitBreakerProperties props;
    private final CircuitBreakerRegistry                 registry;
    private final MessageCenterClient                    messageCenterClient;
    private final ConfigResolver                         configResolver;
    private final String                                 applicationName;

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        try {
            if (!props.isEnabled()) return invocation.proceed();

            MappedStatement ms = (MappedStatement) invocation.getArgs()[0];
            SqlCommandType sqlType = ms.getSqlCommandType();
            if (sqlType == SqlCommandType.UNKNOWN || sqlType == SqlCommandType.FLUSH) {
                return invocation.proceed();
            }

            // 6 参数重载时 args[5] 已是 BoundSql，其余通过参数对象动态获取
            BoundSql boundSql = invocation.getArgs().length == 6
                    ? (BoundSql) invocation.getArgs()[5]
                    : ms.getBoundSql(invocation.getArgs()[1]);

            String fingerprint = SqlFingerprintUtils.extract(boundSql.getSql());
            String circuitKey  = sqlType.name() + ":" + SqlFingerprintUtils.hash(fingerprint);

            // 按优先级解析配置：ThreadLocal > 方法注解 > 接口注解 > 全局配置
            ResolvedConfig config = configResolver.resolve(ms, sqlType);
            if (config.isDisableCircuitBreaker()) return invocation.proceed();

            CircuitBreakerState state = registry.getOrCreate(circuitKey);
            if (state.isOpen()) {
                String openAt = formatTs(state.getOpenTimestamp());
                // 快速失败日志节流：同一 key 每 5 秒只输出一次，防止高并发下日志风暴
                if (state.shouldLogFastFail(5000)) {
                    log.error("[SqlCircuitBreaker] 快速失败 | key={} | mapper={} | sql={} | 熔断时间={} | 熔断时长={}ms",
                            circuitKey, ms.getId(), fingerprint, openAt, state.getCircuitOpenMs());
                }
                throw new SqlCircuitBreakerException(
                        buildFailMessage(ms, circuitKey, fingerprint, sqlType, state, openAt), circuitKey);
            }

            long start = System.nanoTime();
            try {
                Object result = invocation.proceed();
                long cost = (System.nanoTime() - start) / 1_000_000;

                if (cost > config.getTimeout()) {
                    handleTimeout(ms, sqlType, circuitKey, fingerprint, cost, config, state);
                } else {
                    state.onSuccess();
                }
                return result;
            } catch (SqlCircuitBreakerException e) {
                throw e;
            } catch (Throwable t) {
                // SQL 执行异常直接透传，不触发熔断
                throw t;
            }
        } finally {
            // 兜底清理 ThreadLocal，防止线程池复用场景下配置泄漏
            XWFrameworkSqlCircuitBreakerContext.clear();
        }
    }

    private void handleTimeout(MappedStatement ms, SqlCommandType sqlType,
                               String circuitKey, String fingerprint,
                               long cost, ResolvedConfig config, CircuitBreakerState state) {
        log.error("[SqlCircuitBreaker] 执行超时 | key={} | mapper={} | sql={} | 耗时={}ms | 超时阈值={}ms",
                circuitKey, ms.getId(), fingerprint, cost, config.getTimeout());
        boolean triggered = state.onTimeout(config.getFailureThreshold(), config.getCircuitOpenMs());
        if (triggered) {
            String openAt    = formatTs(state.getOpenTimestamp());
            String recoverAt = formatTs(state.getOpenTimestamp() + state.getCircuitOpenMs());
            log.error("[SqlCircuitBreaker] 熔断开启 | key={} | 熔断时长={}ms | 开始={} | 预计恢复={}",
                    circuitKey, state.getCircuitOpenMs(), openAt, recoverAt);
            sendMessage(buildEvent(ms, fingerprint, sqlType, state, config.getTimeout(), cost)
                    .setEventType("CIRCUIT_OPEN"));
        }
    }
}
```

### 4.5 熔断注册中心

```java
public class CircuitBreakerRegistry {

    // SQL 指纹 → 熔断状态
    private final ConcurrentHashMap<String, CircuitBreakerState> registry = new ConcurrentHashMap<>();

    public CircuitBreakerState getOrCreate(String circuitKey) {
        CircuitBreakerState s = registry.computeIfAbsent(circuitKey, CircuitBreakerState::new);
        s.touch(); // 更新 lastActiveTime，供 evictIdle 判断
        return s;
    }

    /**
     * 定时清理长期无活动的条目，防止内存泄漏（无论 CLOSED / OPEN）。
     * 被清理后若请求再次到来，getOrCreate 会重新创建 CLOSED 状态，等同于熔断自然消散。
     */
    @Scheduled(fixedDelay = 300_000)
    public void evictIdle() {
        long now = System.currentTimeMillis();
        registry.entrySet().removeIf(e -> now - e.getValue().getLastActiveTime() > 600_000);
    }
}
```

`CircuitBreakerState` 关键方法：

```java
public class CircuitBreakerState {

    /**
     * 是否处于 OPEN 状态。
     * 每次调用时检查是否到期：若 circuitOpenMs 已过则自动重置为 CLOSED（double-check + synchronized）。
     */
    public boolean isOpen() {
        if (state == State.CLOSED) return false;
        if (System.currentTimeMillis() - openTimestamp > circuitOpenMs) {
            synchronized (this) {
                if (state == State.OPEN) {
                    state = State.CLOSED;
                    consecutiveFail = 0;
                    log.info("[SqlCircuitBreaker] 熔断到期，自动重置为 CLOSED | key={}", sqlFingerprint);
                }
            }
            return false;
        }
        return true;
    }

    /** 超时时调用，返回 true 表示本次触发了熔断（连续计数达到阈值） */
    public synchronized boolean onTimeout(int failureThreshold, long circuitOpenMs) {
        consecutiveFail++;
        if (consecutiveFail >= failureThreshold) {
            state = State.OPEN;
            openTimestamp = System.currentTimeMillis();
            this.circuitOpenMs = circuitOpenMs;
            consecutiveFail = 0;
            return true;
        }
        return false;
    }

    /** SQL 正常执行（未超时）时重置连续失败计数 */
    public void onSuccess() {
        consecutiveFail = 0;
    }

    /** 由 getOrCreate 调用，刷新活跃时间 */
    public void touch() {
        lastActiveTime = System.currentTimeMillis();
    }

    public long getLastActiveTime() { return lastActiveTime; }
    public long getOpenTimestamp()  { return openTimestamp; }
    public long getCircuitOpenMs()  { return circuitOpenMs; }
}
```

### 4.6 配置解析器

```java
public class ConfigResolver {

    private final XWFrameworkSqlCircuitBreakerProperties global;

    /**
     * 按优先级解析：ThreadLocal > 方法注解 > 接口注解 > 全局配置。
     * 注解解析结果缓存在 ConcurrentHashMap 中，避免每次 SQL 执行重复反射。
     */
    public ResolvedConfig resolve(MappedStatement ms, SqlCommandType sqlType) {
        XWFrameworkSqlCircuitBreakerConfig tl = XWFrameworkSqlCircuitBreakerContext.get();

        // 从缓存获取注解解析结果（首次触发反射解析）
        AnnotationPair pair = annotationCache.computeIfAbsent(ms.getId(), k -> {
            XWFrameworkSqlCircuitBreaker methodAnn = resolveMethodAnnotation(ms);
            XWFrameworkSqlCircuitBreaker ifaceAnn  = resolveInterfaceAnnotation(ms);
            return new AnnotationPair(methodAnn, ifaceAnn);
        });

        return ResolvedConfig.builder()
                .timeout(mergeTimeout(sqlType, tl, pair.method, pair.iface))
                .circuitOpenMs(mergeCircuitOpenMs(tl, pair.method, pair.iface))
                .failureThreshold(mergeFailureThreshold(sqlType, tl, pair.method, pair.iface))
                .disableCircuitBreaker(mergeDisable(tl, pair.method, pair.iface))
                .build();
    }

    private long mergeTimeout(SqlCommandType type,
                              XWFrameworkSqlCircuitBreakerConfig tl,
                              XWFrameworkSqlCircuitBreaker method,
                              XWFrameworkSqlCircuitBreaker iface) {
        if (tl != null) {
            Long v = tl.getTimeout(type);
            if (v != null && v >= 0) return v;
        }
        if (method != null) {
            long v = annotationTimeout(method, type);
            if (v >= 0) return v;
        }
        if (iface != null) {
            long v = annotationTimeout(iface, type);
            if (v >= 0) return v;
        }
        return global.getTimeout(type);
    }

    /**
     * failureThreshold 优先级：ThreadLocal > 方法注解 > 接口注解 > 全局（按 SQL 类型区分）。
     * ThreadLocal 和注解不区分 SELECT/DML，全局配置才按类型拆分。
     */
    private int mergeFailureThreshold(SqlCommandType sqlType,
                                      XWFrameworkSqlCircuitBreakerConfig tl,
                                      XWFrameworkSqlCircuitBreaker method,
                                      XWFrameworkSqlCircuitBreaker iface) {
        if (tl != null && tl.getFailureThreshold() != null && tl.getFailureThreshold() > 0) {
            return tl.getFailureThreshold();
        }
        if (method != null && method.failureThreshold() > 0) return method.failureThreshold();
        if (iface != null && iface.failureThreshold() > 0) return iface.failureThreshold();
        return global.getFailureThreshold(sqlType);
    }
}
```

### 4.7 消息中心接入

直接引入团队现有的消息中心 starter，无需 SPI 抽象。拦截器内定义事件模型，组装后调用消息中心 Bean 发送即可。

事件 DTO（starter 内部定义，与消息中心 API 适配）：

```java
@Data
@Accessors(chain = true)
public class CircuitBreakerEvent {
    /** 系统标识（spring.application.name） */
    private String applicationName;
    /** Mapper 全限定名 + 方法，如 com.example.OrderMapper.queryByUserId */
    private String mapperId;
    /** SQL 指纹 */
    private String sqlFingerprint;
    /** SQL 类型 */
    private String sqlType;
    /** 实际执行耗时（毫秒） */
    private long cost;
    /** 超时阈值（毫秒） */
    private long timeoutThreshold;
    /** 熔断持续时长（毫秒） */
    private long circuitOpenMs;
    /** 事件发生时间戳 */
    private long eventTime;
    /** 事件类型：当前仅有 CIRCUIT_OPEN（快速失败不发消息，避免高并发下消息风暴） */
    private String eventType;
}
```

拦截器中直接注入消息中心 Bean（`MessageCenterClient` 替换为实际类型）：

消息中心 Bean 由消息中心 starter 自动注入，AutoConfiguration 直接声明依赖即可，无需业务系统做任何额外配置（详见 10.3 节）。

### 4.8 业务异常定义

```java
public class SqlCircuitBreakerException extends RuntimeException {

    private final String circuitKey;

    public SqlCircuitBreakerException(String message, String circuitKey) {
        super(message);
        this.circuitKey = circuitKey;
    }

    public String getCircuitKey() {
        return circuitKey;
    }
}
```

异常 message 格式：

```
[SqlCircuitBreaker] 熔断已开启，请求快速失败
  mapper   = com.example.mapper.OrderMapper.queryByUserId
  SQL类型  = SELECT
  key      = SELECT:a3f2c1d9...
  sql      = select * from orders where user_id = ?
  熔断时间 = 2026-04-28 10:23:45
  熔断时长 = 60000ms
```

---

## 5. SQL 指纹提取工具

```java
public class SqlFingerprintUtils {

    // 字符串字面量必须最先替换，防止其内容被误当作注释处理
    private static final Pattern STRING_LITERAL_PATTERN = Pattern.compile("'(?:[^']|'')*'");
    private static final Pattern SINGLE_LINE_COMMENT_PATTERN = Pattern.compile("--[^\n]*");
    private static final Pattern MULTI_LINE_COMMENT_PATTERN = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\b\\d+\\b");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    /**
     * 提取 SQL 指纹：替换字符串字面量 → 去注释 → 小写 → 替换数字为 ? → 合并空白。
     * 用于日志展示，保留可读性。
     */
    public static String extract(String sql) {
        if (sql == null) return "";
        String s = STRING_LITERAL_PATTERN.matcher(sql).replaceAll("?");
        s = SINGLE_LINE_COMMENT_PATTERN.matcher(s).replaceAll("");
        s = MULTI_LINE_COMMENT_PATTERN.matcher(s).replaceAll("");
        s = s.toLowerCase().trim();
        s = NUMBER_PATTERN.matcher(s).replaceAll("?");
        return WHITESPACE_PATTERN.matcher(s).replaceAll(" ");
    }

    /**
     * 对已提取的 SQL 指纹取 MD5，用作熔断 Key。固定 32 位，避免超长 SQL 撑大 Key。
     * 调用方先调 extract() 得到指纹（用于日志），再调此方法得到 Key，避免重复计算。
     */
    public static String hash(String fingerprint) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(fingerprint.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return fingerprint;
        }
    }
}
```

---

## 6. 完整配置示例

### 6.1 全局配置（application.yml）

```yaml
sql-circuit-breaker:
  enabled: true
  select-timeout-ms: 30000         # SELECT 超时 30s
  insert-timeout-ms: 10000         # INSERT 超时 10s
  update-timeout-ms: 10000         # UPDATE 超时 10s
  delete-timeout-ms: 10000         # DELETE 超时 10s
  circuit-open-ms: 60000           # 熔断持续 60s，到期自动重置为 CLOSED
  select-failure-threshold: 3      # SELECT 连续 3 次超时才熔断
  dml-failure-threshold: 1         # DML 1 次超时即熔断
```

### 6.2 接口级注解

```java
// 整个 Mapper 接口的 SELECT 超时设置为 5s，其余继承全局配置
@XWFrameworkSqlCircuitBreaker(selectTimeout = 5000)
public interface OrderMapper extends BaseMapper<Order> {
    List<Order> queryByUserId(Long userId);

    // 该方法覆盖接口级配置：SELECT 超时 2s，熔断持续 30s
    @XWFrameworkSqlCircuitBreaker(selectTimeout = 2000, circuitOpenMs = 30000)
    List<Order> complexQuery(QueryParam param);

    // 该方法禁用熔断（只执行，不熔断，适合人工触发的查询）
    @XWFrameworkSqlCircuitBreaker(disableCircuitBreaker = true)
    List<Order> adminQuery(AdminParam param);
}
```

### 6.3 ThreadLocal 编程式

```java
// 场景：当前请求临时放宽超时限制
try {
    XWFrameworkSqlCircuitBreakerContext.setTimeout(60_000, 10_000, 10_000, 10_000);
    List<Order> result = orderMapper.complexQuery(param);
    return result;
} finally {
    XWFrameworkSqlCircuitBreakerContext.clear();
}

// 场景：当前请求完全跳过熔断（如定时任务补偿）
try {
    XWFrameworkSqlCircuitBreakerContext.disableCircuitBreaker();
    orderMapper.repairData(ids);
} finally {
    XWFrameworkSqlCircuitBreakerContext.clear();
}
```

---

## 7. 日志格式规范

所有日志使用统一前缀 `[SqlCircuitBreaker]`，便于 ELK 等日志系统过滤。

| 事件 | 级别 | 关键字段 |
|---|---|---|
| SQL 执行超时（未触发熔断） | ERROR | key, mapper, sql, cost, 超时阈值 |
| 熔断打开 | ERROR | key, 熔断时长, 开始时间, 预计恢复时间 |
| 快速失败（节流：同 key 每 5s 一条） | ERROR | key, mapper, sql, 熔断时间, 熔断时长 |
| 熔断到期自动重置为 CLOSED | INFO | key |
| 定时清理空闲状态 | DEBUG | 清理数量 |

熔断打开时的日志示例：

```
[SqlCircuitBreaker] 执行超时 | key=SELECT:a3f2c1d9ef... | mapper=com.example.mapper.OrderMapper.queryByUserId | sql=select * from order where user_id = ? and status = ? | 耗时=32145ms | 超时阈值=30000ms
[SqlCircuitBreaker] 熔断开启 | key=SELECT:a3f2c1d9ef... | 熔断时长=60000ms | 开始=2026-04-28 10:23:45 | 预计恢复=2026-04-28 10:24:45
```

---

## 8. 改动范围

| 模块 | 内容 |
|---|---|
| `XWFrameworkSqlCircuitBreakerInterceptor` | 核心拦截器，注册为 MyBatis Plus 拦截器（MP 自动收集） |
| `CircuitBreakerRegistry` | 熔断状态注册中心，持有所有 SQL 指纹的状态 |
| `CircuitBreakerState` | 单个 SQL 指纹的两状态（CLOSED/OPEN）状态机 |
| `XWFrameworkSqlCircuitBreakerProperties` | 全局配置映射（application.yml） |
| `@XWFrameworkSqlCircuitBreaker` | 接口/方法级注解 |
| `XWFrameworkSqlCircuitBreakerContext` | ThreadLocal 编程式工具 |
| `XWFrameworkSqlCircuitBreakerConfig` | ThreadLocal 携带的配置对象 |
| `ConfigResolver` | 多优先级配置合并（含注解解析缓存） |
| `SqlFingerprintUtils` | SQL 指纹提取 |
| `CircuitBreakerEvent` | 事件 DTO（组装后传给消息中心） |
| `SqlCircuitBreakerException` | 业务异常 |
| `SqlCircuitBreakerAutoConfiguration` | Spring Boot 自动装配（starter） |

---

## 9. 注意事项

1. **ThreadLocal 必须 clear**：在 finally 块中调用 `XWFrameworkSqlCircuitBreakerContext.clear()`，否则在线程池复用场景下会污染下一次请求。拦截器的 finally 块会兜底清理一次，但业务代码自己也应在 finally 中显式清理。

2. **ThreadLocal disableCircuitBreaker 的使用场景**：当某个操作明知 SQL 会慢（如定时任务数据修复、人工补偿脚本），但又不希望触发熔断影响正常业务时，可通过 ThreadLocal 临时关闭熔断，作用范围仅限当前线程本次调用，不影响其他线程：

   ```java
   try {
       XWFrameworkSqlCircuitBreakerContext.disableCircuitBreaker();
       orderMapper.batchFixData(ids);   // 该次 SQL 不触发熔断
   } finally {
       XWFrameworkSqlCircuitBreakerContext.clear();
   }
   ```

   > 该设置优先级最高，会覆盖注解和全局配置；关闭后 SQL 正常执行，超时不计入熔断计数。

4. **SQL 指纹碰撞**：极少数情况下不同 SQL 结构会产生相同指纹，可根据实际需要在指纹前拼接 `mapperId` 降低碰撞概率。

5. **熔断粒度**：当前粒度是 `SQL类型:SQL指纹`。若需要更细粒度（如按 mapperId + SQL），可在 circuitKey 中加入 `ms.getId()`。

7. **不对异常熔断**：只对超时熔断，SQL 执行抛出的其他异常（如连接异常、语法错误）不纳入熔断计数，避免误判。

8. **快速失败高频日志**：熔断期间每次匹配都记录 ERROR 日志，高并发下日志量可能很大，可酌情降为 WARN 或在同一 key 上做日志限流。

9. **消息中心只发一次**：消息通知仅在熔断**首次打开**时触发（`handleTimeout` 中 `triggered=true` 分支），快速失败路径不发消息。熔断期间每个请求都发消息会在高并发下瞬间打爆消息中心，务必注意。

10. **`SELECT ... FOR UPDATE` 类型误判风险**：MyBatis 根据 XML 标签（`<select>`）确定 SQL 类型，`SELECT ... FOR UPDATE` 会被识别为 SELECT，走 `selectTimeoutMs` 和 `selectFailureThreshold` 的宽松阈值。但该语句本质上持行锁，锁不释放会阻塞其他事务，危害与 DML 相当。建议对此类方法单独加注解，手动收紧阈值：

    ```java
    // 覆盖为 DML 级阈值：超时阈值缩短、1 次即熔断
    @XWFrameworkSqlCircuitBreaker(selectTimeout = 3000, failureThreshold = 1)
    List<Order> selectForUpdate(Long userId);
    ```

    > 此外，若开发者将 INSERT/UPDATE 等语句误写在 `<select>` 标签内，熔断器同样无法感知类型错误，需通过 code review 保证 Mapper 标签与 SQL 语义一致。

12. **三层配置校验机制**：SDK 对全局配置、注解、ThreadLocal 编程式配置均做值域校验，规则统一如下：

    | 配置项 | 合法值 | 非法值及后果 |
    |---|---|---|
    | `*-timeout-ms` | `> 0` | `= 0` 任何 SQL 都超时；`< 0` 永不超时 |
    | `circuit-open-ms` | `> 0` | `<= 0` 熔断后立即重置为 CLOSED，保护失效 |
    | `*-failure-threshold` | `>= 1` | `= 0` 永远无法触发熔断 |

    三层校验的触发时机不同：

    | 配置来源 | 校验时机 | 报错类型 |
    |---|---|---|
    | 全局 `application.yml` | **启动时**，`validate()` 在 AutoConfiguration 中调用 | `IllegalStateException`，阻止启动 |
    | `@XWFrameworkSqlCircuitBreaker` 注解 | **首次 SQL 执行时**，注解运行时按需加载，无法在启动期统一扫描 | `IllegalArgumentException`，含 mapper 路径 |
    | ThreadLocal `set()` | **调用 set() 时立即**，让业务方在设值处就感知错误 | `IllegalArgumentException` |

13. **多实例负载均衡下的阈值行为**：熔断状态存储在各实例自己的内存中，多实例部署时每个实例**独立计数、互不感知**，配置的阈值应理解为"单实例阈值"而非集群整体阈值。

    | 场景 | 实际影响 |
    |---|---|
    | DB 整体变慢（所有慢查询） | 影响较小，各实例独立收敛，最终全部熔断 |
    | 流量不均（某实例集中承压） | 影响较大，只有承压实例触发熔断，其他实例仍持续打 DB |
    | 偶发抖动（单次慢查询） | per-instance 反而是优势，单实例抖动不会误触发全局熔断 |

    **配置建议**：在实例数较多、流量分布不均的场景下，可适当调低 `select-failure-threshold` 和 `dml-failure-threshold`，使单实例更快收敛。若需要跨实例协同熔断，需引入 Redis 共享状态（会增加 Redis 故障对熔断器本身的影响，复杂度显著上升，当前版本不支持，可按需规划）。

14. **日志输出落到业务方自己的日志文件**：SDK 使用标准 SLF4J（`LoggerFactory.getLogger(...)`），不绑定具体日志实现，日志输出完全由业务方的 `logback.xml` / `log4j2.xml` 控制，与业务日志落在同一个文件中。

    快速失败（`FAST_FAIL`）期间只打日志、不发消息通知，原因是高并发下快速失败会连续触发，若每次都发消息会产生消息风暴；同一 circuitKey 在节流窗口（5s）内只输出一条日志，避免日志量过大。仅在熔断**首次打开**（`CIRCUIT_OPEN`）时发一条消息通知。

    如需将 SDK 日志单独隔离，可在 `logback.xml` 中按包名配置独立 Appender：

    ```xml
    <logger name="com.xw.tmp.starter.framework.circuitbreaker" level="WARN" additivity="false">
        <appender-ref ref="CIRCUIT_BREAKER_FILE"/>
    </logger>
    ```

    这样熔断相关日志单独落一个文件，排查熔断问题时不用在全量业务日志里过滤。

