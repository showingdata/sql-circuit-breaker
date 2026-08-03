# 基于分布式微服务架构中的熔断器思想  设计一款 SQL超时熔断器 springboot-stater

> 基于 MyBatis / MyBatis-Plus Interceptor 的慢 SQL 观测与后续快速失败 SDK

[![Maven Central](https://img.shields.io/maven-central/v/io.github.showingdata.starter.framework/sql-circuit-breaker-spring-boot-starter?color=blue)](https://central.sonatype.com/artifact/io.github.showingdata.starter.framework/sql-circuit-breaker-spring-boot-starter)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![JDK](https://img.shields.io/badge/JDK-8%2B-brightgreen)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.x%20%7C%203.x-brightgreen)](https://spring.io/projects/spring-boot)
[![GitHub Stars](https://img.shields.io/github/stars/showingdata/sql-circuit-breaker?style=social)](https://github.com/showingdata/sql-circuit-breaker)
[![GitHub last commit](https://img.shields.io/github/last-commit/showingdata/sql-circuit-breaker)](https://github.com/showingdata/sql-circuit-breaker)
[![MyBatis](https://img.shields.io/badge/ORM-MyBatis%20%7C%20MyBatis--Plus-orange)](https://mybatis.org/)

## 版本迭代记录

| 序号 | 版本号 | 功能说明 | 修订人 |
|---|---|---|---|
| 1 | 2.2.x | 基于 MyBatis / MyBatis-Plus 拦截器实现慢 SQL 观测、超时计数、熔断快速失败、多级配置、消息通知与 Metrics 指标。 | chenjiang |
| 2 | 3.0.0 | 新增熔断 Key 粒度可配（fingerprint / table / datasource）与线程池 / `@Async` 跨线程上下文传播能力，默认行为向后兼容。 | chenjiang |

## v3.0.0 更新

> 版本号 2.2.x → 3.0.0，主旋律是**粒度灵活化 + 异步上下文传播**。默认行为完全向后兼容，存量配置无需改动即可升级。

### 1. 熔断 Key 粒度可配（`key-granularity`）

历史版本熔断粒度固定为 `数据源:SQL类型:SQL指纹`，DB 级/表级故障时保护碎片化、迟迟不触发。3.0.0 抽出 `CircuitBreakerKeyStrategy` SPI，三档粒度 yml 一键切换：

| 粒度 | 适用故障 | 触发速度 |
|---|---|---|
| `fingerprint`（默认） | 单条慢 SQL | 慢 |
| `table` | 表级故障（锁/索引/数据膨胀） | 快 |
| `datasource` | DB 级故障（宕机/网络分区） | 最快 |

```yaml
sql-circuit-breaker:
  key-granularity: table   # 默认 fingerprint，与历史行为一致
```

`table` 粒度按 `msId + rawSql` 组合缓存表名，正确处理 `${tableName}` 动态分表 SQL（如 `order_202607` / `order_202608` 各自独立熔断，不互相污染）。详见 [§3.5](#35-熔断-key-粒度key-granularity)。

### 2. 线程池 / `@Async` 跨线程上下文传播

`SqlCircuitBreakerContext` 基于 `ThreadLocal`，跨线程不传播——主线程 `set` 的配置在线程池 worker 上丢失，Mapper 走 yml 默认值而非预期覆盖。3.0.0 提供两个零侵入方案：

- **新线程池**：`SqlCircuitBreakerThreadPoolExecutor`，替换 `new ThreadPoolExecutor(...)` 即自动透传
- **已有线程池**：`SqlCircuitBreakerTaskUtils.wrap(task)`，提交处一行包装

包装器在 worker 上「先 clear 清残留 → set 还原快照 → 执行 → finally clear 防泄漏」，并做防御性拷贝隔离提交后的 `setTimeout` 等 mutate。详见 [§4.9](#49-线程池async-跨线程上下文传播)。

### 升级指南

- 默认 `fingerprint` 粒度与历史 Key **逐字符一致**，熔断状态不重置，零改动升级
- ThreadLocal 编程式 API 不变，新 wrapper 为可选项，不引入新强依赖
- JDK 8+ / Spring Boot 2.x，无破坏性变更

## 快速接入

> **版本对应关系（请按 Spring Boot 版本选择对应 artifactId / 分支）**
>
> | Spring Boot | JDK | artifactId | 分支 |
> |---|---|---|---|
> | 2.x | 8+ | `sql-circuit-breaker-spring-boot-starter` | `master` |
> | 3.x | 17+ | `sql-circuit-breaker-spring-boot3-starter` | `springboot3` |
>
> 两个 starter 共用同一份 `sql-circuit-breaker-core`，核心熔断逻辑完全一致，仅自动装配机制和依赖坐标按 Spring Boot 版本适配。

### 1. 引入依赖

**Spring Boot 2.x:**

```xml
<dependency>
    <groupId>io.github.showingdata.starter.framework</groupId>
    <artifactId>sql-circuit-breaker-spring-boot-starter</artifactId>
    <version>3.0.0</version>
</dependency>
```

**Spring Boot 3.x:**

```xml
<dependency>
    <groupId>io.github.showingdata.starter.framework</groupId>
    <artifactId>sql-circuit-breaker-spring-boot3-starter</artifactId>
    <version>3.0.0</version>
</dependency>
```

### 2. application.yml 配置

```yaml
sql-circuit-breaker:
  enabled: true
  select:
    timeout-ms: 10000                    # SELECT 超时阈值（毫秒）
    failure-threshold: 3                 # 连续超时几次触发熔断
    circuit-open-ms: 60000               # 熔断持续时长（毫秒）
    cache-max-size: 10000                # 熔断状态缓存最大条目数
  insert:
    timeout-ms: 5000
    failure-threshold: 1
    circuit-open-ms: 30000
    cache-max-size: 5000
  update:
    timeout-ms: 5000
    failure-threshold: 1
    circuit-open-ms: 30000
    cache-max-size: 5000
  delete:
    timeout-ms: 5000
    failure-threshold: 1
    circuit-open-ms: 30000
    cache-max-size: 5000
```

四种 SQL 类型（SELECT / INSERT / UPDATE / DELETE）均为必填，缺少任意一块启动时会报错提示。

接入完成，重启即生效，无需修改任何业务代码。

> **边界说明**：SDK 在 MyBatis 拦截器层统计 SQL 实际执行耗时。某次 SQL 执行完成后，如果耗时超过 `timeout-ms`，才会计入熔断失败次数；达到阈值后，后续相同 SQL 指纹会在本地快速失败，不再发送到 DB。SDK 不会中断或取消已经发送到数据库、正在执行中的 JDBC SQL；如需强制取消执行中的 SQL，请配合数据库驱动、连接池或 MyBatis/JDBC 查询超时能力使用。

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
| 慢 SQL 检测 | 按 SQL 类型（SELECT/INSERT/UPDATE/DELETE）独立配置耗时阈值，SQL 执行完成后根据实际耗时判断是否超阈值 |
| 自动熔断 | 连续超阈值后自动进入熔断状态，熔断期间相同 SQL 指纹本地快速失败，不发送到 DB |
| 多级配置 | 全局配置 → Mapper 接口注解 → Mapper 方法注解 → ThreadLocal 编程式覆盖 |
| 快速失败 | 熔断期间抛出指定业务异常，记录结构化错误日志 |
| 消息通知 | 实现 `MessageCenterClient` 接口即可接入自有通知渠道，默认空操作 |
| 多数据源隔离 | 实现 `DataSourceKeyResolver` 接口即可适配任意数据源框架（如 dynamic-datasource），默认基于 MyBatis Environment ID |
| 可观测性 | 引入 Micrometer（spring-boot-actuator）后自动暴露 5 项指标，无需额外配置 |


## 2. 整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                        业务系统                                  │
│ Mapper 接口 / 方法                                              │
│ @SqlCircuitBreaker(timeoutMs=5000)                              │
│ SqlCircuitBreakerContext.setTimeout(...)  ← ThreadLocal 编程式  │
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
│  6. 全程上报 Micrometer 指标                                    │
└───────────────────────────┬─────────────────────────────────────┘
                            │
              ┌─────────────┴──────────────┐
              ▼                            ▼
  CircuitBreakerRegistry          MessageCenterClient（可自定义实现）
  （4 个独立 Guava Cache，           默认 NoOpMessageCenterClient
   按 SQL 类型分别管理）
  SQL指纹 → CircuitBreakerState
  状态：CLOSED / OPEN

DataSourceKeyResolver（可自定义实现）
默认 DefaultDataSourceKeyResolver（基于 MyBatis Environment ID）
适配 dynamic-datasource 等运行时切换框架
```


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

熔断 Key 为 `datasource_id:sql_type:fingerprint_md5`，例如 `default:SELECT:a3f2c1...`，前缀数据源标识用于多数据源场景下隔离各数据源的熔断状态，SQL 指纹取 MD5 避免超长 Key。

Key 第三段粒度可配（`key-granularity`），支持 fingerprint / table / datasource 三档，详见 [§3.5](#35-熔断-key-粒度key-granularity)。

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

注解和 ThreadLocal 是**粗粒度覆盖**：对该 Mapper / 方法下所有 SQL 类型统一生效，无法针对 SELECT / DML 分别设置。
全局配置文件是**细粒度配置**：每种 SQL 类型独立精确控制，是 per-type 差异化配置的唯一入口。

### 3.4 内置缓存（Guava Cache）

`CircuitBreakerRegistry` 为 4 种 SQL 类型分别维护一个独立的 Guava Cache，用于存储每条 SQL 指纹对应的熔断状态：

```
selectCache:  circuitKey → CircuitBreakerState
insertCache:  circuitKey → CircuitBreakerState
updateCache:  circuitKey → CircuitBreakerState
deleteCache:  circuitKey → CircuitBreakerState
```

**双重驱逐策略**：

| 驱逐策略 | 配置来源 | 作用 |
|---|---|---|
| LRU 容量上限 | `cache-max-size`（按 SQL 类型独立配置） | 内存硬上界，防止无限增长占满内存，超出后驱逐最久未访问的条目 |
| 访问过期（expireAfterAccess） | 由 `circuit-open-ms` 推导：取 20 倍且不低于 5 分钟，**不单独配置** | 某条 SQL 长期未被访问时自动移出，防止长期不活跃的 SQL 驻留内存 |

**为什么访问过期不开放配置**：它必须显著大于熔断窗口（`circuit-open-ms`），否则空闲期处于 OPEN 的熔断器会被缓存提前驱逐、削弱保护。直接由 `circuit-open-ms` 推导可从根上杜绝这类误配；内存硬上界仍由 `cache-max-size` 保证，访问过期只承担空闲清理与 `open.count` Gauge 的自愈。

**各类型独立配置的意义**：SELECT 场景通常 SQL 种类多（各种查询条件组合），`cache-max-size` 建议设大（如 10000）；DML 场景 SQL 种类相对少，可设小（如 5000）节省内存。

### 3.5 熔断 Key 粒度（key-granularity）

熔断 Key 决定"哪些 SQL 共享同一个熔断器"。粒度越细，隔离越精确但越容易碎片化；粒度越粗，故障响应越快但误伤面越大。三档可选：

| 粒度 | Key 格式 | 适用故障 | 触发速度 | 误伤范围 |
|---|---|---|---|---|
| `fingerprint`（默认） | `ds:sqlType:fingerprintHash` | 单条慢 SQL（执行计划退化） | 慢（按 SQL 模板独立计数） | 仅该条 SQL |
| `table` | `ds:sqlType:tableName` | 表级故障（锁、索引缺失、数据膨胀） | 快（同表所有 SQL 共享计数） | 同表同类型所有 SQL |
| `datasource` | `ds:sqlType` | DB 级故障（宕机、网络分区、连接池耗尽） | 最快（同库同类型全部共享） | 同库同类型全部 SQL |

**典型场景对比**：假设 `orders` 表索引被误删，有 50 条不同 SQL 打它，阈值都是 10：

- `fingerprint`：50 条 SQL 各自计数，需 500 次失败才全部保护 → 保护碎片化
- `table`：50 条 SQL 共享一个"orders 表熔断器"，10 次失败即全部快失败
- `datasource`：整个 DB 的所有 SELECT 共享，10 次失败即全部 SELECT 快失败

**配置方式**：

```yaml
sql-circuit-breaker:
  key-granularity: table   # 默认 fingerprint，可选 table / datasource
```

默认 `fingerprint` 与历史行为完全一致，无需改动即可升级，存量熔断 Key 不变。

**table 粒度的表名提取**：

- 正则匹配 `FROM`/`INTO`/`UPDATE`/`DELETE FROM` 后的表名，取首个表，小写化
- schema 限定名 `db.orders` 保留完整
- JOIN 取首个表（驱动表）
- 子查询 `FROM (SELECT id FROM orders) t` 提取内层物理表 `orders`
- 派生表无具名表 `FROM (SELECT 1) t` → 解析失败 → 回退到 fingerprintHash（降级为指纹粒度，无回归）
- 表名按 `msId + rawSql` 组合缓存：静态 SQL（`#{}` 参数化）rawSql 固定命中缓存只解析一次；动态表名 SQL（`${tableName}` 字符串替换）产生不同 rawSql，各自独立缓存，按月分表的 `order_202607` / `order_202608` 不会互相污染

**datasource 粒度的边界**：

粒度到 `dsKey:sqlType`，不跨 SQL 类型共享（SELECT 与 INSERT 仍独立计数），避免改造 `CircuitBreakerRegistry` 的四 Cache 结构。DB 宕机场景下各类型会各自达阈值快速失败，实践上不影响 DB 级故障的保护效果。

**自定义粒度**：

实现 `CircuitBreakerKeyStrategy` 接口并声明为 Bean，三个默认实现自动让位（`@ConditionalOnMissingBean`）：

```java
import io.github.showingdata.starter.framework.circuitbreaker.keystrategy.CircuitBreakerKeyStrategy;
import io.github.showingdata.starter.framework.circuitbreaker.keystrategy.CircuitBreakerKeyContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CustomKeyStrategyConfig {
    @Bean
    public CircuitBreakerKeyStrategy circuitBreakerKeyStrategy() {
        return new MyKeyStrategy();  // 例如按 业务域+表 粒度
    }
}
```

## 4. 使用说明

### 4.1 全局配置（application.yml）

```yaml
sql-circuit-breaker:
  enabled: true
  auto-inject: true                    # 默认 true：SqlSessionFactory 未注册拦截器时自动兜底注入
  key-granularity: fingerprint         # 默认 fingerprint：按 SQL 指纹；可选 table（按表名）/ datasource（按数据源+SQL类型），见 §3.5
  select:
    timeout-ms: 10000                      # SELECT 超时阈值（毫秒），建议 10s
    failure-threshold: 3                   # 连续超时几次触发熔断，SELECT 建议 3
    circuit-open-ms: 60000                 # 熔断持续时长（毫秒），建议 60s
    cache-max-size: 10000                  # 熔断状态缓存最大条目数（LRU 驱逐）
  insert:
    timeout-ms: 5000                       # DML 建议 5s
    failure-threshold: 1                   # DML 持锁影响大，建议 1 次即熔断
    circuit-open-ms: 30000
    cache-max-size: 5000
  update:
    timeout-ms: 5000
    failure-threshold: 1
    circuit-open-ms: 30000
    cache-max-size: 5000
  delete:
    timeout-ms: 5000
    failure-threshold: 1
    circuit-open-ms: 30000
    cache-max-size: 5000
```

`auto-inject` 默认开启。正常情况下 MyBatis / MyBatis-Plus 会自动收集 Spring 容器中的 `Interceptor` Bean；如果业务系统手动构建 `SqlSessionFactory`、使用 dynamic-datasource 等多数据源框架，或自定义 MyBatis 配置导致自动收集失效，SDK 会在 `SqlSessionFactory` 初始化后检查拦截器链，发现缺少 `SqlCircuitBreakerInterceptor` 时自动补充注入。该机制不会替换业务已有的 `SqlSessionFactory`，不会修改数据源配置，也不会额外添加分页插件等 MyBatis-Plus 插件。

### 4.2 注解配置

注解字段对标注的 Mapper / 方法下所有 SQL 类型统一生效（粗粒度覆盖）。
若需要按 SELECT / DML 独立配置，请使用 application.yml 全局配置。

```java
// 接口级：该 Mapper 所有 SQL 超时改为 5s
@SqlCircuitBreaker(timeoutMs = 5000)
public interface OrderMapper extends BaseMapper<Order> {

    // 方法级：超时 2s，熔断持续 30s（覆盖接口级配置）
    @SqlCircuitBreaker(timeoutMs = 2000, circuitOpenMs = 30000)
    List<Order> complexQuery(QueryParam param);

    // 禁用熔断（适合人工触发的管理查询）
    @SqlCircuitBreaker(disableCircuitBreaker = true)
    List<Order> adminQuery(AdminParam param);

    // SELECT ... FOR UPDATE：收紧阈值，1 次超时即熔断
    @SqlCircuitBreaker(timeoutMs = 3000, failureThreshold = 1)
    List<Order> selectForUpdate(Long userId);
}
```

### 4.3 启动期注解校验

默认情况下，`@SqlCircuitBreaker` 注解会在对应 Mapper 首次执行 SQL 时做值域校验，并将解析结果写入缓存。若希望在应用启动阶段提前发现注解配置错误，可开启启动期注解校验：

```yaml
sql-circuit-breaker:
  validate-annotations-on-startup: true
```

开启后，SDK 会在 Spring 单例 Bean 初始化完成后遍历所有 `SqlSessionFactory` 中的 `MappedStatement`，提前解析 Mapper 接口和方法上的 `@SqlCircuitBreaker` 注解，并校验以下规则：

| 注解字段 | 合法值 |
|---|---|
| `timeoutMs` | `-1` 表示继承上层配置，或 `> 0` |
| `circuitOpenMs` | `-1` 表示继承上层配置，或 `> 0` |
| `failureThreshold` | `-1` 表示继承上层配置，或 `>= 1` |

如果发现非法配置，应用启动会 fail-fast，避免问题延迟到首次调用该 Mapper 时才暴露。校验通过后，注解解析结果会被预热到缓存中，首次真实 SQL 请求无需再做反射解析。

该功能默认关闭：

- 关闭时不影响功能正确性，仍会在首次执行对应 Mapper 时进行懒校验。
- 开启后只校验 `@SqlCircuitBreaker` 注解值域，不会执行 SQL，也不会改变熔断状态。
- 若枚举 `MappedStatement` 失败，SDK 会打印 WARN 并退回运行期懒校验，不会因为校验器自身问题阻塞启动。

### 4.4 ThreadLocal 编程式

```java
// 场景：当前请求临时放宽超时限制（对所有 SQL 类型统一生效）
try {
    SqlCircuitBreakerContext.setTimeout(60_000);
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

可用的快捷方法：

| 方法 | 说明 |
|---|---|
| `setTimeout(long ms)` | 覆盖当前线程所有 SQL 类型的超时阈值 |
| `setCircuitOpenMs(long ms)` | 覆盖熔断持续时长 |
| `setFailureThreshold(int n)` | 覆盖连续超时触发熔断次数 |
| `disableCircuitBreaker()` | 完全跳过熔断检测 |
| `clear()` | 清理 ThreadLocal（必须在 finally 中调用） |

### 4.5 消息通知接入

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

### 4.6 多数据源标识适配（DataSourceKeyResolver）

熔断 Key 中包含数据源标识，用于多数据源场景下隔离各数据源的熔断状态。默认实现 `DefaultDataSourceKeyResolver` 基于 MyBatis `Environment ID`。

若使用 `dynamic-datasource-spring-boot-starter` 等运行时切换框架（所有数据源共用一个 `SqlSessionFactory`，无法通过 Environment ID 区分），可实现 `DataSourceKeyResolver` 接口并注册为 Spring Bean 覆盖默认行为：

```java
@Component
public class DynamicDataSourceKeyResolver implements DataSourceKeyResolver {
    @Override
    public String resolve(MappedStatement ms) {
        // dynamic-datasource 通过 ThreadLocal 记录当前数据源 key
        String dsKey = DynamicDataSourceContextHolder.peek();
        return dsKey != null ? dsKey : "master";
    }
}
```

其他框架同理，只需在 `resolve()` 中返回能唯一标识当前数据源的字符串即可。若项目只有单数据源，无需任何配置，默认返回 `"default"`，不受影响。

### 4.7 Metrics 指标（Micrometer）

#### 激活方式

引入 `spring-boot-actuator` 即可，SDK 自动检测并激活真实指标实现，无需任何额外配置：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

未引入 `spring-boot-actuator` 时，所有指标降级为空操作，SDK 正常运行不受任何影响。

#### 指标清单

SDK 自动注册以下 5 项指标：

| 指标名 | 类型 | 标签 | 说明 |
|---|---|---|---|
| `sql.circuit.breaker.intercept.total` | Counter | `sql_type` | 拦截器处理的 SQL 总次数（UNKNOWN/FLUSH 不计入） |
| `sql.circuit.breaker.timeout` | Counter | `sql_type`, `mapper_id` | SQL 执行超时次数（耗时超过阈值） |
| `sql.circuit.breaker.open` | Counter | `sql_type`, `mapper_id` | 熔断器开启次数（CLOSED → OPEN） |
| `sql.circuit.breaker.fast.fail` | Counter | `sql_type`, `mapper_id` | 熔断期间快速失败次数 |
| `sql.circuit.breaker.open.count` | Gauge | 无 | 当前处于 OPEN 状态的熔断器数量（实时） |

标签说明：
- `sql_type`：SQL 类型，值为 `SELECT` / `INSERT` / `UPDATE` / `DELETE`
- `mapper_id`：Mapper 全限定方法名，如 `com.example.mapper.OrderMapper.queryByUserId`

> 所有 5 项指标均在启动时完成预注册，`/actuator/metrics` 首次访问即可见全部指标名称，无需等待 SQL 执行或熔断事件发生。其中 `timeout` / `open` / `fast.fail` 预注册时以空字符串作为 `mapper_id` 占位，运行期动态注册的真实 Mapper 条目与之独立共存。

#### 高基数场景：关闭 mapper_id 标签

`timeout` / `open` / `fast.fail` 三项指标默认带 `mapper_id` 标签，**单服务的时间序列数 ≈ Mapper 方法数 × 4 × 3**。大型系统（数百 Mapper × 多副本 × 多服务）下 Prometheus 时间序列容易膨胀（同时也增加 VictoriaMetrics / Mimir 等按 series 计费后端的成本）。

可通过配置关闭 `mapper_id` 标签，三项指标退化为仅按 `sql_type` 聚合，时间序列数从 N × 12 收敛到固定 12：

```yaml
sql-circuit-breaker:
  metrics:
    include-mapper-id: false   # 默认 true；规模大或对 series 基数敏感时关掉
```

关闭后定位具体 Mapper 改用日志中的 mapper 字段，例如：
```
[SqlCircuitBreaker] 熔断开启 | key=default:SELECT:a3f2c1... | mapper=com.example.mapper.OrderMapper.queryByUserId | ...
```

`intercept.total`（无 mapper_id）和 `open.count`（Gauge 无标签）不受此开关影响。

#### 访问端点

通过 `/actuator/metrics` 查看指标：

```yaml
# application.yml 开放端点（按需配置）
management:
  endpoints:
    web:
      exposure:
        include: metrics, prometheus
```

```
# 查看某项指标当前值
GET /actuator/metrics/sql.circuit.breaker.open.count

# 按 sql_type 过滤
GET /actuator/metrics/sql.circuit.breaker.timeout?tag=sql_type:SELECT
```

#### Prometheus + Grafana 告警示例

```promql
# 近 5 分钟内各 Mapper 快速失败速率（用于告警触发）
rate(sql_circuit_breaker_fast_fail_total[5m])

# 近 5 分钟内超时速率（按 SQL 类型聚合）
sum by (sql_type) (rate(sql_circuit_breaker_timeout_total[5m]))

# 当前处于 OPEN 状态的熔断器数量（> 0 即告警）
sql_circuit_breaker_open_count

# 熔断开启频率（近 1 小时，按 Mapper 排列）
topk(10, sum by (mapper_id) (increase(sql_circuit_breaker_open_total[1h])))
```

建议对 `sql_circuit_breaker_open_count > 0` 配置即时告警，该 Gauge 归零说明所有熔断器已自动恢复，无需手动处理。

#### 自定义指标实现

若项目未使用 Micrometer（如使用其他监控体系），可实现 `SqlCircuitBreakerMetrics` 接口并注册为 Spring Bean 接入自有监控：

```java
@Component
public class CustomCircuitBreakerMetrics implements SqlCircuitBreakerMetrics {

    @Override
    public void recordIntercept(String sqlType) { ... }

    @Override
    public void recordTimeout(String sqlType, String mapperId) { ... }

    @Override
    public void recordOpen(String sqlType, String mapperId) { ... }

    @Override
    public void recordFastFail(String sqlType, String mapperId) { ... }
}
```

SDK 通过 `@ConditionalOnMissingBean` 检测，存在自定义实现时自动跳过 Micrometer 实现的注册。

### 4.8 业务调用栈定位（推荐：全局异常处理）

`SqlCircuitBreakerException` 重写了 `fillInStackTrace()` 跳过堆栈填充，规避高并发快速失败下频繁填栈的 CPU/内存开销。代价是异常对象本身**不带堆栈**，业务方仅靠该异常无法定位具体调用方代码行。

好在 MyBatis 抛出时会用 `MyBatisSystemException` 二次包装并填栈，**包装异常的堆栈包含完整的业务调用链**（Controller → Service → Mapper 代理 → MyBatis 拦截器链）。Web 项目加一个 `@RestControllerAdvice` 即可统一接住快速失败并打印业务调用栈：

```java
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MyBatisSystemException.class)
    public ResponseEntity<Map<String, Object>> handleMyBatis(MyBatisSystemException ex) {
        SqlCircuitBreakerException cb = findCircuitBreaker(ex);
        if (cb != null) {
            // 关键：用包装异常打栈，里面有 Controller / Service / Mapper 调用行号
            log.error("[GlobalExceptionHandler] SQL 熔断快速失败 | key={} | 业务调用栈见下方",
                    cb.getCircuitKey(), ex);
            return circuitBreakerResponse(cb);
        }
        log.error("[GlobalExceptionHandler] MyBatis 异常: {}", ex.getMessage(), ex);
        return errorResponse("db_error", ex.getMessage());
    }

    @ExceptionHandler(SqlCircuitBreakerException.class)
    public ResponseEntity<Map<String, Object>> handleCircuitBreaker(SqlCircuitBreakerException ex) {
        // 兜底：未被 MyBatis 包装直抛的极少数场景，无堆栈可用
        log.error("[GlobalExceptionHandler] SQL 熔断快速失败（无包装栈）| key={} | msg={}",
                ex.getCircuitKey(), ex.getMessage());
        return circuitBreakerResponse(ex);
    }

    private SqlCircuitBreakerException findCircuitBreaker(Throwable ex) {
        Throwable cur = ex;
        while (cur != null) {
            if (cur instanceof SqlCircuitBreakerException) {
                return (SqlCircuitBreakerException) cur;
            }
            cur = cur.getCause();
        }
        return null;
    }

    private ResponseEntity<Map<String, Object>> circuitBreakerResponse(SqlCircuitBreakerException cb) {
        Map<String, Object> body = new HashMap<>();
        body.put("status", "circuit_open");
        body.put("circuitKey", cb.getCircuitKey());
        body.put("msg", cb.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    private ResponseEntity<Map<String, Object>> errorResponse(String status, String msg) {
        Map<String, Object> body = new HashMap<>();
        body.put("status", status);
        body.put("msg", msg);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
```

接入后日志效果：

```
[ERROR] ... GlobalExceptionHandler - [GlobalExceptionHandler] SQL 熔断快速失败 | key=default:SELECT:a3f2c1... | 业务调用栈见下方
org.mybatis.spring.MyBatisSystemException: nested exception is ...
    at org.mybatis.spring.MyBatisExceptionTranslator.translateExceptionIfPossible(...)
    ...
    at com.example.service.OrderService.queryByUser(OrderService.java:42)        ← 业务行号
    at com.example.controller.DemoController.listOrders(DemoController.java:88)  ← Controller 行号
    ...
```

> **注意**：业务方在 Controller / Service 中用 `catch (SqlCircuitBreakerException e)` 直接捕获是**捕获不到**的——实际抛出的类型是包装后的 `MyBatisSystemException`，`instanceof` 不匹配。统一通过全局 advice 接住，不要在业务代码里直接 try/catch 该异常。

### 4.9 线程池/`@Async` 跨线程上下文传播

`SqlCircuitBreakerContext` 基于 `ThreadLocal`，主线程 `set` 的配置**不会自动传播到线程池/`@Async`/`CompletableFuture.supplyAsync` 的工作线程**——子线程执行 Mapper 时拿不到主线程的配置。SDK 提供两个零侵入方案：

**方案一（推荐，新项目）：替换线程池**

将 `new ThreadPoolExecutor(...)` 改为 `SqlCircuitBreakerThreadPoolExecutor`，其余代码不动，提交的任务自动透传上下文：

```java
@Configuration
public class ThreadPoolConfig {
    @Bean("orderExecutor")
    public ExecutorService orderExecutor() {
        return new SqlCircuitBreakerThreadPoolExecutor(
            4, 8, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(1000),
            new ThreadFactoryBuilder().setNameFormat("order-pool-%d").build());
    }
}
```

业务方提交时无需任何改动：

```java
orderExecutor.submit(() -> mapper.queryByRegion(region));  // 自动带上主线程 set 的 timeout
```

**方案二（已有线程池不便替换）：提交处手动包装**

```java
// Callable
orderExecutor.submit(SqlCircuitBreakerTaskUtils.wrap(() -> mapper.queryByRegion(region)));
// Runnable
orderExecutor.execute(SqlCircuitBreakerTaskUtils.wrap(() -> mapper.updateStatus(ids)));
```

**包装器的隔离保证**：

- 提交时在调用线程捕获 ThreadLocal 快照（防御性拷贝，隔离提交后的 `setTimeout` 等 mutate）
- 执行前 `clear` 清掉 worker 线程上前一个 raw 任务可能残留的标签，再 `set` 快照
- 执行后 `finally clear` 兜底，防池化线程复用导致标签泄漏到下一个任务

**WebFlux/Reactor 场景**：MyBatis 是阻塞 JDBC，响应式管道调 MyBatis 必然走 `Mono.fromCallable` / `subscribeOn(boundedElastic)`。此时应在 `fromCallable` 内部 set/clear，而非依赖外部 set 传播：

```java
Mono.fromCallable(() -> {
    SqlCircuitBreakerContext.setTimeout(5000);   // 在执行 JDBC 的同一线程上 set
    try { return mapper.query(); }
    finally { SqlCircuitBreakerContext.clear(); }
}).subscribeOn(Schedulers.boundedElastic());
```

## 5. 日志格式

所有日志使用统一前缀 `[SqlCircuitBreaker]`，便于 ELK 等日志系统过滤。

| 事件 | 级别 | 关键字段 |
|---|---|---|
| SQL 执行超时（未触发熔断） | ERROR | key, mapper, sql, cost, 超时阈值 |
| 熔断打开 | ERROR | key, 熔断时长, 开始时间, 预计恢复时间 |
| 快速失败（节流：同 key 每 5s 一条） | ERROR | key, mapper, sql, 熔断时间, 熔断时长 |
| 熔断到期自动重置为 CLOSED | INFO | key |

日志示例：

```
[SqlCircuitBreaker] 执行超时 | key=default:SELECT:a3f2c1d9ef... | mapper=com.example.mapper.OrderMapper.queryByUserId | sql=select * from order where user_id = ? and status = ? | 耗时=32145ms | 超时阈值=10000ms
[SqlCircuitBreaker] 熔断开启 | key=default:SELECT:a3f2c1d9ef... | 熔断时长=60000ms | 开始=2026-05-03 10:23:45 | 预计恢复=2026-05-03 10:24:45
```

如需将 SDK 日志单独隔离，可在 `logback.xml` / `logback-spring.xml` 中配置独立 Appender：

```xml
<configuration>
    <!-- Spring Boot 项目：路径跟随 application.yml 的 logging.file.path -->
    <springProperty scope="context" name="LOG_HOME" source="logging.file.path" defaultValue="./logs"/>

    <!-- 1. 定义 SDK 专属 appender -->
    <appender name="CIRCUIT_BREAKER_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>${LOG_HOME}/sql-circuit-breaker.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>${LOG_HOME}/sql-circuit-breaker.%d{yyyy-MM-dd}.%i.log.gz</fileNamePattern>
            <maxHistory>30</maxHistory>
            <timeBasedFileNamingAndTriggeringPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedFNATP">
                <maxFileSize>50MB</maxFileSize>
            </timeBasedFileNamingAndTriggeringPolicy>
        </rollingPolicy>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%-5level] [%thread] %logger{50} [%line] - %msg%n</pattern>
            <charset>UTF-8</charset>
        </encoder>
    </appender>

    <!-- 2. SDK 包路径下所有日志只走该 appender，不再传播到 root -->
    <logger name="io.github.showingdata.starter.framework.circuitbreaker" level="WARN" additivity="false">
        <appender-ref ref="CIRCUIT_BREAKER_FILE"/>
    </logger>

    <!-- 3. 业务自己的 root 配置保持原样 -->
    <root level="INFO">
        <appender-ref ref="STDOUT"/>
        <!-- 业务原有 appender ... -->
    </root>
</configuration>
```

三个容易踩坑的关键点：

| 关键点 | 说明 |
|---|---|
| `additivity="false"` | **必加**。否则 SDK 日志会同时打到 root 的 appender，业务主日志里依然混入熔断日志，等于没隔离 |
| `level="WARN"` | SDK 的事件日志（超时 / 熔断开启 / 快速失败）都是 ERROR 级，"熔断重置"是 INFO 级。设 WARN 表示只关心异常事件；要全量诊断改成 INFO |
| `<file>` 路径独立 | 不要复用业务的 info / error 文件名，否则失去隔离意义 |


## 6. 注意事项

1. **ThreadLocal 必须由调用方 clear**：在 finally 块中调用 `SqlCircuitBreakerContext.clear()`，否则在线程池复用场景下会污染下一次请求。拦截器**不再做兜底清理**——这样设计是为了让 Service 层 set 一次 ThreadLocal 后，能对其调用的多条 Mapper SQL 统一生效（若拦截器在第一条 SQL 执行后清理，从第二条 SQL 起 ThreadLocal 就会失效）。拦截器在入口对 ThreadLocal 做一次快照，整次 intercept 调用使用同一份快照，不受调用方在执行过程中变更 ThreadLocal 的影响。

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
   @SqlCircuitBreaker(timeoutMs = 5000)
   public interface OrderMapper extends BaseMapper<Order> { ... }

   // ❌ 无效，不会被识别
   @SqlCircuitBreaker(timeoutMs = 5000)
   public class OrderService { ... }
   ```

4. **注解是粗粒度覆盖**：`@SqlCircuitBreaker` 的 `timeoutMs` / `failureThreshold` / `circuitOpenMs` 对该 Mapper / 方法下所有 SQL 类型统一生效。若需要 SELECT 和 DML 使用不同阈值，请在 application.yml 中按类型独立配置，注解仅作为整体覆盖使用。

5. **SQL 指纹碰撞**：极少数情况下不同 SQL 结构会产生相同指纹，可根据实际需要在指纹前拼接 `mapperId` 降低碰撞概率。

6. **熔断粒度**：默认 `数据源ID:SQL类型:SQL指纹`（fingerprint 粒度），可通过 `key-granularity` 切换为 table（按表名）或 datasource（按数据源+SQL类型），适配表级/DB级故障，详见 [§3.5](#35-熔断-key-粒度key-granularity)。

7. **不取消正在执行中的 SQL**：`timeout-ms` 是 SDK 在 MyBatis 拦截器层的耗时判定阈值，不是 JDBC 查询取消时间。一次 SQL 已经发送到 DB 后，SDK 会等待 `invocation.proceed()` 返回或抛出异常，再根据耗时决定是否计入熔断；它不会主动中断数据库侧正在执行的语句。熔断生效后，后续相同 SQL 指纹会在本地快速失败，从而避免继续向 DB 发送同类 SQL。

8. **不对异常熔断**：只对执行完成后的超阈值耗时计入熔断，SQL 执行抛出的其他异常（如连接异常、语法错误）不纳入熔断计数，避免误判。若业务希望把驱动超时、Socket 超时等特定异常也纳入熔断，可在业务侧结合驱动/连接池超时配置和异常处理策略扩展。

9. **消息通知只发一次**：消息通知仅在熔断首次打开时触发，快速失败路径不发消息，避免高并发下消息风暴。

10. **`SELECT ... FOR UPDATE` 误判风险**：MyBatis 根据 XML 标签确定 SQL 类型，`SELECT ... FOR UPDATE` 会被识别为 SELECT，走宽松阈值。建议对此类方法单独加注解收紧阈值：

   ```java
   @SqlCircuitBreaker(timeoutMs = 3000, failureThreshold = 1)
   List<Order> selectForUpdate(Long userId);
   ```

11. **配置校验规则**：

    | 配置项 | 合法值 |
    |---|---|
    | `timeout-ms` | `> 0` |
    | `circuit-open-ms` | `> 0` |
    | `failure-threshold` | `>= 1` |
    | `cache-max-size` | `>= 1` |

    全局配置在启动时校验（缺少任意 SQL 类型块或字段非法均会快速失败）；注解默认在首次 SQL 执行时校验，配置 `sql-circuit-breaker.validate-annotations-on-startup=true` 后可提前到启动期校验；ThreadLocal 在调用 `set()` 时立即校验。

12. **多实例部署**：熔断状态存储在各实例内存中，各实例独立计数、互不感知，配置阈值应理解为单实例阈值。流量分布不均时可适当调低阈值使单实例更快收敛。

13. **SQL 熔断拦截器兜底注入**：SDK 默认开启 `auto-inject`，会在每个 `SqlSessionFactory` 初始化后检查 MyBatis 拦截器链。如果链路中已经存在 `SqlCircuitBreakerInterceptor`，直接跳过；如果不存在，则通过兜底机制补充注入，避免部分业务系统“已引入依赖并配置参数，但拦截器未生效”的问题。

    该机制只补充注册 SQL 熔断拦截器，不会替换 `SqlSessionFactory`，不会修改数据源，也不会自动添加分页插件等 MyBatis-Plus 插件。启动时会输出检查日志：

    ```text
    [SqlCircuitBreaker] 检查 SqlSessionFactory [sqlSessionFactory] 拦截器链 | environmentId=default | interceptorCount=1 | registered=true
    ```

    若触发兜底注入，会输出 WARN 日志：

    ```text
    [SqlCircuitBreaker] 拦截器已通过兜底机制注入到 SqlSessionFactory [sqlSessionFactory]（MyBatis 自动扫描可能失效，建议排查）
    ```

    如业务系统存在特殊 MyBatis 初始化逻辑，需要完全自行控制拦截器注册，可关闭兜底机制：

    ```yaml
    sql-circuit-breaker:
      auto-inject: false
    ```

    关闭后，需自行确保 `SqlCircuitBreakerInterceptor` 已注册到目标 `SqlSessionFactory`。

14. **多数据源场景的熔断隔离**：熔断 Key 包含数据源标识，保证不同数据源的熔断状态互不干扰。根据项目使用的多数据源框架选择适配方式：

    **方式一（推荐）：实现 `DataSourceKeyResolver` 接口**

    适用于 `dynamic-datasource-spring-boot-starter`、Druid 多数据源等运行时切换框架，声明 Bean 覆盖默认实现即可（参见 [4.6 节](#46-多数据源标识适配datasourcekeyresolver)）。

    **方式二：配置 MyBatis Environment ID**

    适用于为每个业务库独立创建 `SqlSessionFactory` 的场景，需为每个工厂显式设置不同的 `environment id`：

    ```java
    @Bean
    public SqlSessionFactory orderSqlSessionFactory(DataSource orderDataSource) throws Exception {
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(orderDataSource);
        factory.setEnvironment("orderDB");  // 必须设置，且各数据源不能重复
        return factory.getObject();
    }

    @Bean
    public SqlSessionFactory userSqlSessionFactory(DataSource userDataSource) throws Exception {
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(userDataSource);
        factory.setEnvironment("userDB");
        return factory.getObject();
    }
    ```

    单数据源无需任何额外配置，默认使用 `"default"` 作为标识，不受影响。

15. **ThreadLocal 上下文与异步场景**：`SqlCircuitBreakerContext` 基于 `ThreadLocal`，跨线程不会自动传播：

    - **`@Async` / `CompletableFuture.supplyAsync` / 自定义线程池**：主线程 `set` 的配置在 worker 线程拿不到，Mapper 调用走全局默认而非预期覆盖值。需使用 `SqlCircuitBreakerThreadPoolExecutor`（替换线程池）或 `SqlCircuitBreakerTaskUtils.wrap`（手动包装），见 [§4.9](#49-线程池async-跨线程上下文传播)。
    - **WebFlux / Reactor**：MyBatis 阻塞 JDBC 必走 `Mono.fromCallable`，应在 `fromCallable` 内部 set/clear，而非依赖外部 set 跨线程传播。
    - **忘 `clear` 会泄漏**：`set()` 后必须在 `finally` 中 `clear()`，否则池化线程下一个任务会读到残留配置造成误路由。使用 `SqlCircuitBreakerThreadPoolExecutor` / `TaskUtils.wrap` 可自动兜底清理，无需业务方手写 finally。
    - **不覆盖的场景**：Reactor 管道的线程切换不经过 JDK 线程池，上述方案不适用，仍需在 `fromCallable` 内部 set/clear。

## 7. 模块说明

| 模块 | 说明 |
|---|---|
| `SqlCircuitBreakerInterceptor` | 核心拦截器，MyBatis / MyBatis-Plus 自动收集注册 |
| `CircuitBreakerCore` | 熔断核心流程（配置解析 → 状态判断 → 计时 → 超时处理），被拦截器复用 |
| `CircuitBreakerRegistry` | 熔断状态注册中心，按 SQL 类型维护 4 个独立 Guava Cache |
| `CircuitBreakerState` | 单个 SQL 指纹的两状态（CLOSED/OPEN）状态机 |
| `SqlCircuitBreakerProperties` | 全局配置映射（application.yml），按 SQL 类型独立配置 |
| `@SqlCircuitBreaker` | 接口/方法级注解（粗粒度统一覆盖） |
| `SqlCircuitBreakerContext` | ThreadLocal 编程式工具（粗粒度统一覆盖） |
| `SqlCircuitBreakerConfig` | ThreadLocal 携带的配置对象 |
| `ConfigResolver` | 多优先级配置合并（含注解解析缓存） |
| `SqlFingerprintUtils` | SQL 指纹提取（含 Mapper 级指纹缓存优化） |
| `CircuitBreakerEvent` | 熔断事件 DTO |
| `MessageCenterClient` | 消息通知扩展接口，默认空实现 |
| `DataSourceKeyResolver` | 数据源标识解析扩展接口，用于多数据源熔断隔离 |
| `DefaultDataSourceKeyResolver` | 默认实现，基于 MyBatis Environment ID |
| `SqlCircuitBreakerException` | 熔断快速失败异常（已重写 fillInStackTrace，高并发下无堆栈填充开销） |
| `SqlCircuitBreakerMetrics` | 指标上报接口，Micrometer 存在时自动切换为真实实现 |
| `MicrometerCircuitBreakerMetrics` | 基于 Micrometer 的指标实现，自动注册 5 项 Counter/Gauge |
| `NoOpCircuitBreakerMetrics` | 空操作实现，Micrometer 不在 classpath 时兜底 |
| `SqlCircuitBreakerAutoConfiguration` | Spring Boot 自动装配 |
