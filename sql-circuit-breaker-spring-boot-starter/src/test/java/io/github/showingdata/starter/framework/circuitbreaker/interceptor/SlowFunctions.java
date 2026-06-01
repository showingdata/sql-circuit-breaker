package io.github.showingdata.starter.framework.circuitbreaker.interceptor;

/**
 * H2 自定义函数，供测试通过 {@code CREATE ALIAS SLOWFN FOR ...} 注册。
 * 按入参毫秒数 sleep，用于在内存库里制造「可控耗时」的 SQL：
 * 入参 &gt; 0 时 sleep 对应毫秒（模拟慢查询），入参为 0 时立即返回（模拟快查询）。
 */
public class SlowFunctions {

    public static int slowFn(int ms) {
        if (ms > 0) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return 0;
    }
}
