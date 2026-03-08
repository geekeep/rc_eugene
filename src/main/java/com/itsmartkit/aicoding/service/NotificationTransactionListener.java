package com.itsmartkit.aicoding.service;

import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

/**
 * RocketMQ 事务消息本地事务监听器。
 *
 * <p>事务消息两阶段流程：
 * <ol>
 *   <li><b>executeLocalTransaction</b>：Half Message 发送成功后，Broker 回调此方法。
 *       此时本地事务（DB 落库）应已在 {@link NotificationDispatchService#accept} 的
 *       {@code @Transactional} 范围内完成。这里只需验证落库是否成功即可。</li>
 *   <li><b>checkLocalTransaction</b>：若 Broker 长时间未收到 Commit/Rollback，
 *       会主动回查此方法。通过查 DB 来决定是 COMMIT 还是 ROLLBACK。</li>
 * </ol>
 *
 * <p>注意：2.3.x 版本起 {@code @RocketMQTransactionListener} 不再需要 {@code txProducerGroup} 参数，
 * 监听器与 Producer 通过 {@code rocketmq.producer.group} 配置自动绑定。
 */
@Component
@RocketMQTransactionListener
public class NotificationTransactionListener implements RocketMQLocalTransactionListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationTransactionListener.class);

    private final NotificationDispatchService dispatchService;

    public NotificationTransactionListener(NotificationDispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    /**
     * Half Message 发送到 Broker 后，立即在此处执行本地事务。
     *
     * <p>由于 {@link NotificationDispatchService#accept} 使用了 {@code @Transactional}，
     * DB 落库与消息发送在同一个业务调用链中完成。本方法只需根据 taskId 确认 DB 是否落库成功。
     *
     * @param msg  事务消息（消息体为 taskId 字符串）
     * @param arg  透传参数（accept 时传入的 taskId Long）
     */
    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        if (arg == null) {
            log.error("executeLocalTransaction: arg(taskId) 为空，ROLLBACK");
            return RocketMQLocalTransactionState.ROLLBACK;
        }

        long taskId = (Long) arg;
        try {
            boolean exists = dispatchService.taskExists(taskId);
            if (exists) {
                log.debug("executeLocalTransaction: taskId={} 已落库，COMMIT", taskId);
                return RocketMQLocalTransactionState.COMMIT;
            } else {
                log.warn("executeLocalTransaction: taskId={} 未落库，ROLLBACK", taskId);
                return RocketMQLocalTransactionState.ROLLBACK;
            }
        } catch (Exception e) {
            // 查询异常时返回 UNKNOWN，让 Broker 稍后回查
            log.error("executeLocalTransaction: taskId={} 查询异常，UNKNOWN: {}", taskId, e.getMessage());
            return RocketMQLocalTransactionState.UNKNOWN;
        }
    }

    /**
     * Broker 主动回查（当 executeLocalTransaction 返回 UNKNOWN，或长时间未收到 Commit/Rollback）。
     *
     * <p>通过查询 DB 任务是否存在来决定最终状态。
     */
    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message msg) {
        String taskIdStr = new String((byte[]) msg.getPayload());
        long taskId;
        try {
            taskId = Long.parseLong(taskIdStr.trim());
        } catch (NumberFormatException e) {
            log.error("checkLocalTransaction: 无法解析 taskId: {}", taskIdStr);
            return RocketMQLocalTransactionState.ROLLBACK;
        }

        try {
            boolean exists = dispatchService.taskExists(taskId);
            RocketMQLocalTransactionState state = exists
                    ? RocketMQLocalTransactionState.COMMIT
                    : RocketMQLocalTransactionState.ROLLBACK;
            log.info("checkLocalTransaction: taskId={}, 状态={}", taskId, state);
            return state;
        } catch (Exception e) {
            log.error("checkLocalTransaction: taskId={} 查询异常，UNKNOWN: {}", taskId, e.getMessage());
            return RocketMQLocalTransactionState.UNKNOWN;
        }
    }
}

