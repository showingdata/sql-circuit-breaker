package io.github.showingdata.starter.framework.circuitbreaker.message;

import io.github.showingdata.starter.framework.circuitbreaker.CircuitBreakerEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author chenjiang
 *
 * 消息中心客户端默认空实现。
 * <p>
 * 当业务方未提供 {@link MessageCenterClient} 实现时，SDK 自动注入此空实现，
 * 避免应用启动失败。熔断事件仅记录 DEBUG 日志，不对外发送。
 * <p>
 * 业务方可通过声明自己的 {@link MessageCenterClient} Bean 覆盖此默认实现。
 */
public class NoOpMessageCenterClient implements MessageCenterClient {

    private static final Logger log = LoggerFactory.getLogger(NoOpMessageCenterClient.class);

    @Override
    public void send(CircuitBreakerEvent event) {
        if (log.isDebugEnabled()) {
            log.debug("[SqlCircuitBreaker] NoOpMessageCenterClient received event: type={}, mapper={}, key={}",
                    event.getEventType(), event.getMapperId(), event.getSqlFingerprint());
        }
    }
}
