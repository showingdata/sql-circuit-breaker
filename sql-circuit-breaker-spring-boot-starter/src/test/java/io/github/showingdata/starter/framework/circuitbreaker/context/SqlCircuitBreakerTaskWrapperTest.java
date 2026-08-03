package io.github.showingdata.starter.framework.circuitbreaker.context;

import io.github.showingdata.starter.framework.circuitbreaker.config.SqlCircuitBreakerConfig;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * SqlCircuitBreakerTaskWrapper 验证：跨线程传播、防泄漏、防御性拷贝、残留清理。
 * 用单线程 executor 保证 worker 线程复用，便于观察泄漏/残留行为。
 */
class SqlCircuitBreakerTaskWrapperTest {

    @Test
    void nullDelegate_failsFast() {
        assertThrows(NullPointerException.class, () -> new SqlCircuitBreakerTaskWrapper<>(null));
        assertThrows(NullPointerException.class, () -> new SqlCircuitBreakerRunnableWrapper(null));
        assertThrows(NullPointerException.class, () -> SqlCircuitBreakerTaskUtils.wrap((Callable<Object>) null));
        assertThrows(NullPointerException.class, () -> SqlCircuitBreakerTaskUtils.wrap((Runnable) null));
    }

    @Test
    void propagatesContextAcrossThread() throws Exception {
        SqlCircuitBreakerContext.setTimeout(5000);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Callable<Long> task = () -> {
                SqlCircuitBreakerConfig cfg = SqlCircuitBreakerContext.get();
                return cfg != null ? cfg.getTimeoutMs() : null;
            };
            Future<Long> f = worker.submit(new SqlCircuitBreakerTaskWrapper<>(task));
            // 无包装时 worker 线程拿不到主线程的 5000；有包装则跨线程传播
            assertEquals(5000L, f.get(5, TimeUnit.SECONDS));
        } finally {
            worker.shutdown();
            SqlCircuitBreakerContext.clear();
        }
    }

    @Test
    void clearsAfterTask_preventsLeak() throws Exception {
        SqlCircuitBreakerContext.setTimeout(5000);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            // 第一个任务：wrapper 在 worker 上 set 后 finally clear
            worker.submit(new SqlCircuitBreakerTaskWrapper<>(() -> null)).get(5, TimeUnit.SECONDS);
            SqlCircuitBreakerContext.clear();  // 清主线程

            // 第二个任务（raw，不包装）跑在同一 worker 线程：不应看到残留的 5000
            Future<SqlCircuitBreakerConfig> f2 = worker.submit(() -> SqlCircuitBreakerContext.get());
            assertNull(f2.get(5, TimeUnit.SECONDS), "worker 线程不应残留上一个任务的上下文");
        } finally {
            worker.shutdown();
            SqlCircuitBreakerContext.clear();
        }
    }

    @Test
    void snapshot_isDefensiveCopy_isolatesPostConstructMutation() throws Exception {
        SqlCircuitBreakerContext.setTimeout(5000);
        try {
            // 构造 wrapper 时快照当前 5000（防御性拷贝）
            SqlCircuitBreakerTaskWrapper<Long> wrapper = new SqlCircuitBreakerTaskWrapper<>(() -> {
                SqlCircuitBreakerConfig cfg = SqlCircuitBreakerContext.get();
                return cfg != null ? cfg.getTimeoutMs() : null;
            });
            // 构造后 mutate 同一 config 对象（setTimeout 内部走 getOrNew 返回现有对象并改字段）
            SqlCircuitBreakerContext.setTimeout(9999);
            // call() 还原的是构造时刻的快照 5000，不是 mutate 后的 9999
            assertEquals(5000L, wrapper.call());
        } finally {
            SqlCircuitBreakerContext.clear();
        }
    }

    @Test
    void nullSnapshot_clearsResidualBeforeDelegate() throws Exception {
        // 主线程不 set 任何上下文，snapshot 为 null
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            // 步骤1：模拟一个 raw 任务泄漏——在 worker 上 set 但不 clear
            worker.submit(() -> {
                SqlCircuitBreakerContext.setTimeout(7777);
                // 故意不 clear，模拟业务方忘记 finally 清理
            }).get(5, TimeUnit.SECONDS);

            // 步骤2：提交包装任务（snapshot=null，因主线程无上下文）
            // delegate 应读到 null，而不是残留的 7777——靠 call() 开头的 clear 兜底
            Future<Long> f = worker.submit(new SqlCircuitBreakerTaskWrapper<>(() -> {
                SqlCircuitBreakerConfig cfg = SqlCircuitBreakerContext.get();
                return cfg != null ? cfg.getTimeoutMs() : null;
            }));
            Long timeout = f.get(5, TimeUnit.SECONDS);
            assertNull(timeout, "wrapper 应在 delegate 执行前清掉 worker 上残留的 7777");
        } finally {
            worker.shutdown();
            SqlCircuitBreakerContext.clear();
        }
    }
}
