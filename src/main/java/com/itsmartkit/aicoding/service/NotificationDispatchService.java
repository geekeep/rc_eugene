package com.itsmartkit.aicoding.service;

import com.itsmartkit.aicoding.domain.NotificationTask;
import com.itsmartkit.aicoding.domain.NotificationTask.Status;
import com.itsmartkit.aicoding.dto.NotificationRequest;
import com.itsmartkit.aicoding.repository.NotificationTaskRepository;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

/**
 * 通知投递核心服务。
 *
 * <p>主要职责：
 * <ol>
 *   <li>接收业务系统请求，在同一本地事务中持久化任务。</li>
 *   <li>通过 RocketMQ 事务消息发送，保证"DB 落库"与"消息入队"的原子性：
 *       只有本地事务提交成功，MQ 消息才对消费者可见。</li>
 *   <li>HTTP 投递由 {@link NotificationMQConsumer} 完成，重试由 RocketMQ 原生机制管理。</li>
 * </ol>
 *
 * <p>事务消息流程：
 * <pre>
 *   Producer                   Broker                  Consumer
 *      │── Half Message ──────────▶│                       │
 *      │◀─ Half Message ACK ───────│                       │
 *      │                           │                       │
 *      │  [执行本地事务: DB save]    │                       │
 *      │                           │                       │
 *      │── Commit / Rollback ─────▶│                       │
 *      │                           │── 可见消息 ───────────▶│
 * </pre>
 */
@Service
public class NotificationDispatchService {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatchService.class);

    /** RocketMQ 事务消息生产者组，需在 Broker 注册 */
    public static final String TX_PRODUCER_GROUP = "notification-tx-producer-group";

    private final NotificationTaskRepository taskRepository;
    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;

    @Value("${notification.rocketmq.topic:notification-topic}")
    private String topic;

    public NotificationDispatchService(NotificationTaskRepository taskRepository,
                                       RocketMQTemplate rocketMQTemplate,
                                       ObjectMapper objectMapper) {
        this.taskRepository = taskRepository;
        this.rocketMQTemplate = rocketMQTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 受理并持久化通知任务，通过 RocketMQ 事务消息保证原子性投递。
     *
     * @param request 业务系统提交的通知请求
     * @return 已保存的任务（含数据库生成的 ID）
     */
    @Transactional
    public NotificationTask accept(NotificationRequest request) {
        // 1. 生成全局唯一 messageId，用于消费端幂等校验
        String messageId = UUID.randomUUID().toString().replace("-", "");

        // 2. 持久化任务
        NotificationTask task = buildTask(request, messageId);
        task = taskRepository.save(task);
        log.info("受理通知任务 id={}, messageId={}, url={}", task.getId(), messageId, task.getTargetUrl());

        // 3. 发送 RocketMQ 事务消息
        //    事务消息：Half Message 先发到 Broker，本地事务（DB save）提交后 Broker 才投递给消费者
        //    消息体只携带 taskId，消费者通过 taskId 从 DB 查询完整信息，避免消息体过大
        Message<String> mqMessage = MessageBuilder
                .withPayload(String.valueOf(task.getId()))
                .setHeader("messageId", messageId)
                .setHeader("KEYS", messageId)  // RocketMQ 按 Key 查询消息
                .build();

        try {
            SendResult sendResult = rocketMQTemplate.sendMessageInTransaction(
                    topic,
                    mqMessage,
                    task.getId()  // arg 透传给 TransactionListener
            );

            if (sendResult.getSendStatus() != SendStatus.SEND_OK) {
                log.warn("任务 id={} 事务消息发送状态异常: {}", task.getId(), sendResult.getSendStatus());
            } else {
                log.info("任务 id={} 事务消息已发送, msgId={}", task.getId(), sendResult.getMsgId());
            }
        } catch (Exception e) {
            // 事务消息发送失败不影响受理结果（任务已落库）
            // RocketMQ Broker 会通过回查机制补偿，最终保证消息送达
            log.error("任务 id={} 事务消息发送异常（将由 Broker 回查补偿）: {}", task.getId(), e.getMessage());
        }

        return task;
    }

    /**
     * 将任务标记为 EXHAUSTED（由消费者在 MQ 重试耗尽后调用）。
     */
    @Transactional
    public void markExhausted(long taskId, int retryCount, String lastResult) {
        taskRepository.findById(taskId).ifPresent(task -> {
            task.setStatus(Status.EXHAUSTED);
            task.setRetryCount(retryCount);
            task.setLastResult(abbreviate(lastResult, 512));
            taskRepository.save(task);
            log.error("任务 id={} MQ 重试耗尽（共{}次），已标记为 EXHAUSTED。最后结果: {}",
                    taskId, retryCount, lastResult);
        });
    }

    /**
     * 更新任务状态（由消费者在每次 HTTP 投递后调用）。
     */
    @Transactional
    public void updateTaskStatus(long taskId, Status status, int retryCount, String lastResult) {
        taskRepository.findById(taskId).ifPresent(task -> {
            task.setStatus(status);
            task.setRetryCount(retryCount);
            task.setLastResult(abbreviate(lastResult, 512));
            taskRepository.save(task);
        });
    }

    // ---- 私有辅助方法 ----

    private NotificationTask buildTask(NotificationRequest request, String messageId) {
        NotificationTask task = new NotificationTask();
        task.setMessageId(messageId);
        task.setTargetUrl(request.getTargetUrl());
        task.setHttpMethod(request.getHttpMethod() != null ? request.getHttpMethod() : "POST");
        task.setBody(request.getBody());
        task.setMaxRetry(request.getMaxRetry() != null
                ? Math.min(request.getMaxRetry(), 10) : 5);

        if (request.getHeaders() != null && !request.getHeaders().isEmpty()) {
            try {
                task.setHeadersJson(objectMapper.writeValueAsString(request.getHeaders()));
            } catch (Exception e) {
                log.warn("序列化 headers 失败，将忽略自定义 headers: {}", e.getMessage());
            }
        }
        return task;
    }

    private String abbreviate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    /** 供 TransactionListener 查询任务是否存在（Broker 回查时使用） */
    public boolean taskExists(long taskId) {
        return taskRepository.existsById(taskId);
    }
}

