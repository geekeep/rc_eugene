package com.itsmartkit.aicoding.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 通知任务实体，持久化到数据库以保证投递的可靠性。
 *
 * <p>状态流转：
 * <pre>
 *   PENDING  ──(MQ消费)──▶  PROCESSING  ──(HTTP 2xx)──▶  SUCCESS
 *                                │
 *                         (HTTP 非2xx/异常)
 *                                │
 *                                ▼
 *                    RocketMQ 自动重试（Retry Topic）
 *                                │
 *                      (达到最大重试次数)
 *                                │
 *                                ▼
 *                    EXHAUSTED（进入 DLQ，人工处理）
 * </pre>
 *
 * <p>重试由 RocketMQ 原生机制管理，本实体不再维护 nextRetryAt 字段。
 */
@Entity
@Table(name = "notification_task",
        indexes = {
                @Index(name = "idx_status", columnList = "status"),
                @Index(name = "idx_message_id", columnList = "messageId", unique = true)
        })
public class NotificationTask {

    public enum Status {
        /** 任务已落库，等待 MQ 消费 */
        PENDING,
        /** MQ 消费者正在执行 HTTP 投递 */
        PROCESSING,
        /** 投递成功 */
        SUCCESS,
        /** 投递失败（MQ 侧自动重试中） */
        FAILED,
        /** RocketMQ 重试耗尽，消息进入 DLQ，需人工介入 */
        EXHAUSTED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * RocketMQ 消息唯一 ID，用于幂等性校验。
     * 消费者处理前先检查该字段，防止因 MQ 重投导致的重复 HTTP 请求。
     */
    @Column(nullable = false, unique = true, length = 64)
    private String messageId;

    /** 目标投递地址 */
    @Column(nullable = false, length = 2048)
    private String targetUrl;

    /** HTTP Method，默认 POST */
    @Column(nullable = false, length = 16)
    private String httpMethod = "POST";

    /**
     * 请求 Headers，以 JSON 字符串存储（key-value 对）。
     * 例如：{"Content-Type":"application/json","X-Token":"abc"}
     */
    @Column(columnDefinition = "TEXT")
    private String headersJson;

    /** 请求 Body，原样存储 */
    @Column(columnDefinition = "TEXT")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.PENDING;

    /** RocketMQ 侧的重试次数（由消费者每次消费时从 Message.getReconsumeTimes() 同步） */
    @Column(nullable = false)
    private int retryCount = 0;

    /** 最大重试次数，透传给 RocketMQ 消费者做超限判断 */
    @Column(nullable = false)
    private int maxRetry = 5;

    /** 最后一次响应摘要（HTTP 状态码或异常信息），便于排查 */
    @Column(length = 512)
    private String lastResult;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // ---- Getters / Setters ----

    public Long getId() { return id; }

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    public String getTargetUrl() { return targetUrl; }
    public void setTargetUrl(String targetUrl) { this.targetUrl = targetUrl; }

    public String getHttpMethod() { return httpMethod; }
    public void setHttpMethod(String httpMethod) { this.httpMethod = httpMethod; }

    public String getHeadersJson() { return headersJson; }
    public void setHeadersJson(String headersJson) { this.headersJson = headersJson; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

    public int getMaxRetry() { return maxRetry; }
    public void setMaxRetry(int maxRetry) { this.maxRetry = maxRetry; }

    public String getLastResult() { return lastResult; }
    public void setLastResult(String lastResult) { this.lastResult = lastResult; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}

