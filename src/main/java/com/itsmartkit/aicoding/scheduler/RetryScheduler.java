package com.itsmartkit.aicoding.scheduler;

import com.itsmartkit.aicoding.domain.NotificationTask;
import com.itsmartkit.aicoding.repository.NotificationTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 定时告警调度器。
 *
 * <p>引入 RocketMQ 后，重试逻辑完全由 RocketMQ 原生机制负责（Retry Topic + DLQ），
 * 本调度器职责简化为：<b>定期扫描 EXHAUSTED（重试耗尽）任务并输出告警日志</b>，
 * 便于运维人员及时发现需要人工干预的失败通知。
 *
 * <p>生产环境可在此处集成告警系统（如钉钉、PagerDuty、Sentry 等）。
 */
@Component
public class RetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetryScheduler.class);

    /** 每次最多告警的任务数，避免日志刷屏 */
    private static final int ALERT_BATCH_SIZE = 100;

    private final NotificationTaskRepository taskRepository;

    public RetryScheduler(NotificationTaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    /**
     * 定期扫描 EXHAUSTED 任务，输出告警日志。
     * 间隔由 {@code notification.scheduler.interval-ms} 控制，默认 60 秒。
     */
    @Scheduled(fixedDelayString = "${notification.scheduler.interval-ms:60000}")
    public void alertExhaustedTasks() {
        List<NotificationTask> exhaustedTasks = taskRepository.findExhaustedTasks(
                PageRequest.of(0, ALERT_BATCH_SIZE));
        if (exhaustedTasks.isEmpty()) {
            return;
        }

        log.error("【告警】发现 {} 个通知任务已重试耗尽，需人工处理：", exhaustedTasks.size());
        for (NotificationTask task : exhaustedTasks) {
            log.error("  ► 任务 id={}, url={}, retryCount={}, 最后结果={}, 创建时间={}",
                    task.getId(),
                    task.getTargetUrl(),
                    task.getRetryCount(),
                    task.getLastResult(),
                    task.getCreatedAt());
            // TODO: 接入企业告警系统（钉钉 / PagerDuty / Sentry 等）
        }
    }
}

