package com.itsmartkit.aicoding.service;

import com.itsmartkit.aicoding.domain.NotificationTask;
import com.itsmartkit.aicoding.domain.NotificationTask.Status;
import com.itsmartkit.aicoding.repository.NotificationTaskRepository;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * RocketMQ 消息消费者，负责执行实际 HTTP 投递。
 *
 * <p>消费语义：
 * <ul>
 *   <li>消费成功（HTTP 2xx）：返回，消息 ACK，不再重试。</li>
 *   <li>消费失败（HTTP 非2xx 或网络异常）：抛出 {@link RuntimeException}，
 *       RocketMQ 自动按延迟级别重投（最多重试 {@code maxReconsumeTimes} 次）。</li>
 *   <li>超过最大重试次数：消息进入 {@code %DLQ%notification-consumer-group} 死信队列，
 *       同时将任务状态更新为 EXHAUSTED。</li>
 * </ul>
 *
 * <p>RocketMQ 默认重试延迟（reconsumeTimes → 延迟）：
 * <pre>
 *   1次  10s | 2次  30s | 3次  1m  | 4次  2m  | 5次  3m
 *   6次  4m  | 7次  5m  | 8次  6m  | 9次  7m  | 10次 8m
 *   ...（最多 16 次，之后进入 DLQ）
 * </pre>
 */
@Service
@RocketMQMessageListener(
        topic = "${notification.rocketmq.topic:notification-topic}",
        consumerGroup = "${notification.rocketmq.consumer-group:notification-consumer-group}",
        maxReconsumeTimes = 16
)
public class NotificationMQConsumer implements RocketMQListener<MessageExt> {

    private static final Logger log = LoggerFactory.getLogger(NotificationMQConsumer.class);

    /** 单次 HTTP 请求超时 */
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    private final NotificationTaskRepository taskRepository;
    private final NotificationDispatchService dispatchService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${notification.rocketmq.max-reconsume-times:16}")
    private int maxReconsumeTimes;

    public NotificationMQConsumer(NotificationTaskRepository taskRepository,
                                  NotificationDispatchService dispatchService,
                                  ObjectMapper objectMapper) {
        this.taskRepository = taskRepository;
        this.dispatchService = dispatchService;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public void onMessage(MessageExt message) {
        String taskIdStr = new String(message.getBody());
        long taskId;
        try {
            taskId = Long.parseLong(taskIdStr.trim());
        } catch (NumberFormatException e) {
            log.error("消息体格式错误，无法解析 taskId: {}", taskIdStr);
            // 格式错误不重试，直接 ACK（消息无效）
            return;
        }

        int reconsumeTimes = message.getReconsumeTimes();
        log.info("开始处理任务 id={}, reconsumeTimes={}", taskId, reconsumeTimes);

        // 从 DB 加载任务
        NotificationTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            log.warn("任务 id={} 不存在，跳过", taskId);
            return;
        }

        // 幂等性校验：SUCCESS 任务不重复投递
        if (task.getStatus() == Status.SUCCESS) {
            log.info("任务 id={} 已成功投递，忽略重复消费", taskId);
            return;
        }

        // 标记为 PROCESSING
        dispatchService.updateTaskStatus(taskId, Status.PROCESSING, reconsumeTimes, null);

        // 执行 HTTP 投递
        boolean success = false;
        String result = "";
        try {
            HttpRequest httpRequest = buildHttpRequest(task);
            HttpResponse<String> response = httpClient.send(httpRequest,
                    HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();
            result = "HTTP " + statusCode;
            success = statusCode >= 200 && statusCode < 300;

            if (!success) {
                log.warn("任务 id={} 投递失败，HTTP状态码={}, reconsumeTimes={}",
                        taskId, statusCode, reconsumeTimes);
            }
        } catch (Exception e) {
            result = "Exception: " + abbreviate(e.getMessage(), 400);
            log.warn("任务 id={} 投递异常: {}, reconsumeTimes={}",
                    taskId, e.getMessage(), reconsumeTimes);
        }

        if (success) {
            dispatchService.updateTaskStatus(taskId, Status.SUCCESS, reconsumeTimes, result);
            log.info("任务 id={} 投递成功", taskId);
        } else {
            // 判断是否已到达最大重试次数
            if (reconsumeTimes >= maxReconsumeTimes) {
                // 超出最大重试次数，标记 EXHAUSTED，消息将进入 DLQ
                dispatchService.markExhausted(taskId, reconsumeTimes, result);
                // 不抛异常：让消息自然进入 DLQ，避免无限循环
            } else {
                dispatchService.updateTaskStatus(taskId, Status.FAILED, reconsumeTimes, result);
                // 抛出异常触发 RocketMQ 自动延迟重试
                throw new RuntimeException("HTTP 投递失败，触发 RocketMQ 重试: taskId=" + taskId
                        + ", result=" + result);
            }
        }
    }

    private HttpRequest buildHttpRequest(NotificationTask task) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(task.getTargetUrl()))
                .timeout(HTTP_TIMEOUT);

        String method = task.getHttpMethod().toUpperCase();
        String body = task.getBody() != null ? task.getBody() : "";
        HttpRequest.BodyPublisher publisher = body.isEmpty()
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        builder.method(method, publisher);

        if (task.getHeadersJson() != null) {
            Map<String, String> headers = objectMapper.readValue(
                    task.getHeadersJson(), new TypeReference<Map<String, String>>() {});
            headers.forEach(builder::header);
        }

        return builder.build();
    }

    private String abbreviate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}

