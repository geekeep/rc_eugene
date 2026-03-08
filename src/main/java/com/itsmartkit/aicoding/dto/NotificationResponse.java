package com.itsmartkit.aicoding.dto;

/**
 * 提交通知后返回给业务系统的响应，仅包含任务 ID 和状态说明。
 * 业务系统不关心最终投递结果，因此只需知道任务已被受理。
 */
public class NotificationResponse {

    private Long taskId;
    private String message;

    public NotificationResponse(Long taskId, String message) {
        this.taskId = taskId;
        this.message = message;
    }

    public Long getTaskId() { return taskId; }
    public String getMessage() { return message; }
}

