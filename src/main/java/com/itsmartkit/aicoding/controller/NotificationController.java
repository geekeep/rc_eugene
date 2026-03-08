package com.itsmartkit.aicoding.controller;

import com.itsmartkit.aicoding.domain.NotificationTask;
import com.itsmartkit.aicoding.dto.NotificationRequest;
import com.itsmartkit.aicoding.dto.NotificationResponse;
import com.itsmartkit.aicoding.repository.NotificationTaskRepository;
import com.itsmartkit.aicoding.service.NotificationDispatchService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 对外暴露的 REST API，供各业务系统提交通知请求。
 *
 * <p>设计原则：业务系统提交请求后立即得到 202 Accepted 响应，
 * 后台异步完成实际 HTTP 投递，与业务系统完全解耦。
 */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationDispatchService dispatchService;
    private final NotificationTaskRepository taskRepository;

    public NotificationController(NotificationDispatchService dispatchService,
                                  NotificationTaskRepository taskRepository) {
        this.dispatchService = dispatchService;
        this.taskRepository = taskRepository;
    }

    /**
     * 提交一条外部通知请求。
     *
     * <p>HTTP 202 Accepted：任务已受理，异步投递中。
     */
    @PostMapping
    public ResponseEntity<NotificationResponse> submit(@Valid @RequestBody NotificationRequest request) {
        NotificationTask task = dispatchService.accept(request);
        NotificationResponse response = new NotificationResponse(
                task.getId(), "任务已受理，正在异步投递");
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    /**
     * 查询指定任务的当前状态（可选，便于排查问题）。
     */
    @GetMapping("/{id}")
    public ResponseEntity<NotificationTask> getTask(@PathVariable Long id) {
        return taskRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}

