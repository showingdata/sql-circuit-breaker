package io.github.showingdata.starter.framework.circuitbreaker.message;

import io.github.showingdata.starter.framework.circuitbreaker.CircuitBreakerEvent;

/**
 * @author chenjiang
 * 消息中心客户端扩展接口。
 * 默认实现为空操作（{@link NoOpMessageCenterClient}），熔断事件不发送任何通知。
 * 业务方可声明自己的 {@link MessageCenterClient} Bean 覆盖默认实现，接入自有消息通知渠道。
 */
public interface MessageCenterClient {

    void send(CircuitBreakerEvent event);
}
