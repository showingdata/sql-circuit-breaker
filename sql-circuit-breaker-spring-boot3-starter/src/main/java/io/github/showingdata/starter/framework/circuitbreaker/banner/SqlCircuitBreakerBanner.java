package io.github.showingdata.starter.framework.circuitbreaker.banner;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * SQL 熔断器 Banner 打印器。
 * Banner 内容存放在 classpath:sql-circuit-breaker-banner.txt，便于维护和自定义。
 * 由 AutoConfiguration 在 ApplicationReadyEvent 时触发，仅在 SDK 启用时输出。
 *
 * @author chenjiang
 */
public class SqlCircuitBreakerBanner {

    private static final String BANNER_RESOURCE = "sql-circuit-breaker-banner.txt";
    private static final String GITHUB = "https://github.com/showingdata/sql-circuit-breaker";
    private static final String GITEE = "https://gitee.com/LanyXP/sql-circuit-breaker";

    public static void print(PrintStream out) {
        printBannerText(out);
        String version = getVersion();
        out.printf(" :: SQL Circuit Breaker :: Spring Boot Starter ::  (v%s)%n", version);
        out.printf(" :: %-71s ::%n", GITHUB);
        out.printf(" :: %-71s ::%n", GITEE);
        out.println();
    }

    private static void printBannerText(PrintStream out) {
        InputStream in = SqlCircuitBreakerBanner.class.getClassLoader().getResourceAsStream(BANNER_RESOURCE);
        if (in == null) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.println(line);
            }
        } catch (Exception ignored) {
            // TODO banner 打印失败不影响主流程
        }
    }

    private static String getVersion() {
        Package pkg = SqlCircuitBreakerBanner.class.getPackage();
        String version = (pkg != null) ? pkg.getImplementationVersion() : null;
        return (version != null) ? version : "2.1.0";
    }
}
