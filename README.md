# 基于分布式微服务架构中的熔断器思想  设计一款 SQL熔断器 springboot-stater

> 基于 MyBatis / MyBatis-Plus Interceptor 的 SQL 超时熔断 SDK

---

## 快速接入

### 1. 引入依赖

```xml
<dependency>
    <groupId>io.github.showingdata.starter.framework</groupId>
    <artifactId>sql-circuit-breaker-spring-boot-starter</artifactId>
    <version>1.0.1</version>
</dependency>
```

### 2. application.yml 配置

```yaml
sql-circuit-breaker:
  enabled: true
  select-timeout-ms: 10000       # SELECT 超时阈值（毫秒）
  insert-timeout-ms: 5000        # INSERT 超时阈值（毫秒）
  update-timeout-ms: 5000        # UPDATE 超时阈值（毫秒）
  delete-timeout-ms: 5000        # DELETE 超时阈值（毫秒）
  circuit-open-ms: 60000         # 熔断持续时长（毫秒），到期自动重置为 CLOSED
  select-failure-threshold: 3    # SELECT 连续超时几次触发熔断
  dml-failure-threshold: 1       # DML 连续超时几次触发熔断
```

接入完成，重启即生效，无需修改任何业务代码。

---

## 1. 背景与目标

### 1.1 问题

业务系统中某些慢 SQL（全表扫描、缺少索引、锁等待）在高并发下会导致：

- 数据库连接池耗尽，引发雪崩
- 上游调用线程大量阻塞，接口超时
- 相同慢 SQL 被重复发送，持续打垮 DB

### 1.2 目标

在 MyBatis / MyBatis-Plus 层面对所有 CRUD SQL 进行拦截，提供：

| 能力 | 说明 |
|---|---|
| 超时检测 | 按 SQL 类型（SELECT/INSERT/UPDATE/DELETE）独立配置超时阈值 |
| 自动熔断 | 超时后自动进入熔断状态，熔断期间相同 SQL 本地快速失败，不发送到 DB |
| 多级配置 | 全局配置 → Mapper 接口注解 → Mapper 方法注解 → ThreadLocal 编程式覆盖 |
| 快速失败 | 熔断期间抛出指定业务异常，记录结构化错误日志 |
| 消息通知 | 实现 `MessageCenterClient` 接口即可接入自有通知渠道，默认空操作 |

---

## 2. 整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                        业务系统                                  │
│ Mapper 接口 / 方法                                              │
│ @SqlCircuitBreaker(selectTimeout=5000)                          │
│ SqlCircuitBreakerContext.set(...)  ← ThreadLocal 编程式覆盖     │
└───────────────────────────┬─────────────────────────────────────┘
                            │ MyBatis / MyBatis-Plus 执行 SQL
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                 SqlCircuitBreakerInterceptor                     │
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
  CircuitBreakerRegistry          MessageCenterClient（可自定义实现）
  （熔断状态存储：内存）            默认 NoOpMessageCenterClient
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

这样设计的好处是：只要这类查询结构有问题（比如缺索引导致全表扫），不管传什么参数都会慢，熔断一次就把整类 SQL 都保护起来了，而不是每个参数值单独计数。

**熔断 Key 的设计：**

熔断 Key 为 `sql_type:fingerprint_md5`，例如 `SELECT:a3f2c1...`，对已提取的 SQL 指纹取 MD5 避免超长 Key。

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

### 3.3 配置优先级（从高到低）

```
ThreadLocal 编程式 > 方法级注解 > 接口级注解 > 全局配置文件
```

---

## 4. 使用说明

### 4.1 全局配置（application.yml）

```yaml
sql-circuit-breaker:
    enabled: true
    # SELECT 超时阈值（毫秒）参考建议: 10s
    select-timeout-ms: 10000
    # INSERT UPDATE DELETE 超时阈值（毫秒）参考建议: 5s
    insert-timeout-ms: 5000
    update-timeout-ms: 5000
    delete-timeout-ms: 5000
    # 熔断持续时长（毫秒），到期自动重置为 CLOSED。参考建议: 60000
    circuit-open-ms: 60000
    # SELECT 连续超时次数阈值，参考建议: 3
    select-failure-threshold: 3
    # DML 连续超时次数阈值，参考建议: 1
    dml-failure-threshold: 1
```

### 4.2 注解配置

```java
// 接口级：该 Mapper 所有 SELECT 超时改为 5s
@SqlCircuitBreaker(selectTimeout = 5000)
public interface OrderMapper extends BaseMapper<Order> {

    // 方法级：SELECT 超时 2s，熔断持续 30s
    @SqlCircuitBreaker(selectTimeout = 2000, circuitOpenMs = 30000)
    List<Order> complexQuery(QueryParam param);

    // 禁用熔断（适合人工触发的管理查询）
    @SqlCircuitBreaker(disableCircuitBreaker = true)
    List<Order> adminQuery(AdminParam param);

    // 覆盖为 DML 级阈值（适合 SELECT ... FOR UPDATE）
    @SqlCircuitBreaker(selectTimeout = 3000, failureThreshold = 1)
    List<Order> selectForUpdate(Long userId);
}
```

### 4.3 ThreadLocal 编程式

```java
// 场景：当前请求临时放宽超时限制
try {
    SqlCircuitBreakerContext.setTimeout(60_000, 10_000, 10_000, 10_000);
    List<Order> result = orderMapper.complexQuery(param);
    return result;
} finally {
    SqlCircuitBreakerContext.clear();  // 必须清理
}

// 场景：当前请求完全跳过熔断（如定时任务补偿）
try {
    SqlCircuitBreakerContext.disableCircuitBreaker();
    orderMapper.repairData(ids);
} finally {
    SqlCircuitBreakerContext.clear();
}
```

### 4.4 消息通知接入

默认不发送任何通知。实现 `MessageCenterClient` 接口并注册为 Spring Bean 即可接入自有通知渠道：

```java
@Component
public class MyMessageCenterClient implements MessageCenterClient {
    @Override
    public void send(CircuitBreakerEvent event) {
        // 接入钉钉、企业微信、短信等通知渠道
    }
}
```

熔断事件 `CircuitBreakerEvent` 包含：应用名、Mapper 方法、SQL 指纹、SQL 类型、耗时、超时阈值、熔断时长、事件时间等字段。

---

## 5. 日志格式

所有日志使用统一前缀 `[SqlCircuitBreaker]`，便于 ELK 等日志系统过滤。

| 事件 | 级别 | 关键字段 |
|---|---|---|
| SQL 执行超时（未触发熔断） | ERROR | key, mapper, sql, cost, 超时阈值 |
| 熔断打开 | ERROR | key, 熔断时长, 开始时间, 预计恢复时间 |
| 快速失败（节流：同 key 每 5s 一条） | ERROR | key, mapper, sql, 熔断时间, 熔断时长 |
| 熔断到期自动重置为 CLOSED | INFO | key |
| 定时清理空闲状态 | DEBUG | 清理数量 |

日志示例：

```
[SqlCircuitBreaker] 执行超时 | key=SELECT:a3f2c1d9ef... | mapper=com.example.mapper.OrderMapper.queryByUserId | sql=select * from order where user_id = ? and status = ? | 耗时=32145ms | 超时阈值=10000ms
[SqlCircuitBreaker] 熔断开启 | key=SELECT:a3f2c1d9ef... | 熔断时长=60000ms | 开始=2026-05-03 10:23:45 | 预计恢复=2026-05-03 10:24:45
```

如需将 SDK 日志单独隔离，可在 `logback.xml` 中配置独立 Appender：

```xml
<logger name="io.github.showingdata.starter.framework.circuitbreaker" level="WARN" additivity="false">
    <appender-ref ref="CIRCUIT_BREAKER_FILE"/>
</logger>
```

---

## 6. 注意事项

1. **ThreadLocal 必须 clear**：在 finally 块中调用 `SqlCircuitBreakerContext.clear()`，否则在线程池复用场景下会污染下一次请求。拦截器的 finally 块会兜底清理一次，但业务代码自己也应在 finally 中显式清理。

2. **disableCircuitBreaker 的使用场景**：当某个操作明知 SQL 会慢（如定时任务数据修复、人工补偿脚本），但又不希望触发熔断影响正常业务时，可通过 ThreadLocal 临时关闭熔断，作用范围仅限当前线程本次调用：

   ```java
   try {
       SqlCircuitBreakerContext.disableCircuitBreaker();
       orderMapper.batchFixData(ids);
   } finally {
       SqlCircuitBreakerContext.clear();
   }
   ```

3. **`@SqlCircuitBreaker` 只能加在 Mapper 接口或接口方法上**：拦截器基于 `MappedStatement` 解析注解，只会在 Mapper 接口类和接口方法上查找，加在 Service 或实现类上不会生效。若需要在 Service 层控制，请使用 `SqlCircuitBreakerContext` ThreadLocal 编程式方式：

   ```java
   // ✅ 有效
   @SqlCircuitBreaker(selectTimeout = 5000)
   public interface OrderMapper extends BaseMapper<Order> { ... }

   // ❌ 无效，不会被识别
   @SqlCircuitBreaker(selectTimeout = 5000)
   public class OrderService { ... }
   ```

4. **SQL 指纹碰撞**：极少数情况下不同 SQL 结构会产生相同指纹，可根据实际需要在指纹前拼接 `mapperId` 降低碰撞概率。

4. **熔断粒度**：当前粒度是 `SQL类型:SQL指纹`。若需要更细粒度（如按 mapperId + SQL），可在 circuitKey 中加入 `ms.getId()`。

5. **不对异常熔断**：只对超时熔断，SQL 执行抛出的其他异常（如连接异常、语法错误）不纳入熔断计数，避免误判。

6. **消息通知只发一次**：消息通知仅在熔断首次打开时触发，快速失败路径不发消息，避免高并发下消息风暴。

7. **`SELECT ... FOR UPDATE` 误判风险**：MyBatis 根据 XML 标签确定 SQL 类型，`SELECT ... FOR UPDATE` 会被识别为 SELECT，走宽松阈值。建议对此类方法单独加注解收紧阈值：

   ```java
   @SqlCircuitBreaker(selectTimeout = 3000, failureThreshold = 1)
   List<Order> selectForUpdate(Long userId);
   ```

8. **三层配置校验**：

   | 配置项 | 合法值 |
   |---|---|
   | `*-timeout-ms` | `> 0` |
   | `circuit-open-ms` | `> 0` |
   | `*-failure-threshold` | `>= 1` |

   全局配置在启动时校验，注解在首次 SQL 执行时校验，ThreadLocal 在调用 `set()` 时立即校验。

9. **多实例部署**：熔断状态存储在各实例内存中，各实例独立计数、互不感知，配置阈值应理解为单实例阈值。流量分布不均时可适当调低阈值使单实例更快收敛。

---

## 7. 模块说明

| 模块 | 说明 |
|---|---|
| `SqlCircuitBreakerInterceptor` | 核心拦截器，MyBatis / MyBatis-Plus 自动收集注册 |
| `CircuitBreakerRegistry` | 熔断状态注册中心，持有所有 SQL 指纹的状态 |
| `CircuitBreakerState` | 单个 SQL 指纹的两状态（CLOSED/OPEN）状态机 |
| `SqlCircuitBreakerProperties` | 全局配置映射（application.yml） |
| `@SqlCircuitBreaker` | 接口/方法级注解 |
| `SqlCircuitBreakerContext` | ThreadLocal 编程式工具 |
| `SqlCircuitBreakerConfig` | ThreadLocal 携带的配置对象 |
| `ConfigResolver` | 多优先级配置合并（含注解解析缓存） |
| `SqlFingerprintUtils` | SQL 指纹提取 |
| `CircuitBreakerEvent` | 熔断事件 DTO |
| `MessageCenterClient` | 消息通知扩展接口，默认空实现 |
| `SqlCircuitBreakerException` | 熔断快速失败异常 |
| `SqlCircuitBreakerAutoConfiguration` | Spring Boot 自动装配 |
