package io.github.showingdata.starter.framework.circuitbreaker.context;

import io.github.showingdata.starter.framework.circuitbreaker.config.SqlCircuitBreakerConfig;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * SqlCircuitBreakerThreadPoolExecutor 验证：零侵入自动包装，submit(Callable) 与 execute(Runnable) 均传播。
 * 所有 submit 变体经 AbstractExecutorService 走 execute，故 execute 的自动包装覆盖全部入口。
 */
class SqlCircuitBreakerThreadPoolExecutorTest {

    @Test
    void executeNull_failsFast() {
        SqlCircuitBreakerThreadPoolExecutor worker = new SqlCircuitBreakerThreadPoolExecutor(
                1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        try {
            assertThrows(NullPointerException.class, () -> worker.execute(null));
        } finally {
            worker.shutdown();
        }
    }

    @Test
    void submitCallable_autoPropagatesContext() throws Exception {
        SqlCircuitBreakerContext.setTimeout(5000);
        SqlCircuitBreakerThreadPoolExecutor worker = new SqlCircuitBreakerThreadPoolExecutor(
                1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        try {
            // 业务方不手动包装，executor 自动透传
            Future<Long> f = worker.submit(() -> {
                SqlCircuitBreakerConfig cfg = SqlCircuitBreakerContext.get();
                return cfg != null ? cfg.getTimeoutMs() : null;
            });
            assertEquals(5000L, f.get(5, TimeUnit.SECONDS));
        } finally {
            worker.shutdown();
            SqlCircuitBreakerContext.clear();
        }
    }

    @Test
    void executeRunnable_autoPropagatesContext() throws Exception {
        SqlCircuitBreakerContext.setTimeout(5000);
        SqlCircuitBreakerThreadPoolExecutor worker = new SqlCircuitBreakerThreadPoolExecutor(
                1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        try {
            // 用一个结果 holder 接收 Runnable 内读到的值
            SqlCircuitBreakerConfig[] holder = new SqlCircuitBreakerConfig[1];
            worker.execute(() -> holder[0] = SqlCircuitBreakerContext.get());
            // execute 异步，等待 worker 处理完
            worker.submit(() -> { }).get(5, TimeUnit.SECONDS);  // 同步屏障：等前面的 execute 执行完
            assertEquals(5000L, holder[0].getTimeoutMs());
        } finally {
            worker.shutdown();
            SqlCircuitBreakerContext.clear();
        }
    }

    @Test
    void noLeakAfterTask() throws Exception {
        SqlCircuitBreakerContext.setTimeout(5000);
        SqlCircuitBreakerThreadPoolExecutor worker = new SqlCircuitBreakerThreadPoolExecutor(
                1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        try {
            worker.submit(() -> null).get(5, TimeUnit.SECONDS);  // 包装任务执行完
            SqlCircuitBreakerContext.clear();  // 清主线程
            // 后续 raw 任务（不包装）跑在同一 worker 线程，应看不到残留
            Future<SqlCircuitBreakerConfig> f = worker.submit(() -> SqlCircuitBreakerContext.get());
            assertNull(f.get(5, TimeUnit.SECONDS));
        } finally {
            worker.shutdown();
            SqlCircuitBreakerContext.clear();
        }
    }
}
