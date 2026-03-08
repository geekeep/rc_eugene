package com.itsmartkit.aicoding.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.util.Map;

/**
 * 业务系统提交的通知请求 DTO。
 *
 * <pre>
 * POST /api/notifications
 * {
 *   "targetUrl": "https://vendor.example.com/callback",
 *   "httpMethod": "POST",
 *   "headers": {
 *     "Content-Type": "application/json",
 *     "X-Api-Key": "secret"
 *   },
 *   "body": "{\"event\":\"USER_REGISTERED\",\"userId\":123}",
 *   "maxRetry": 5
 * }
 * </pre>
 */
public class NotificationRequest {

    /** 目标投递 URL，必填 */
    @NotBlank(message = "targetUrl 不能为空")
    private String targetUrl;

    /**
     * HTTP Method，支持 GET / POST / PUT / PATCH，默认 POST。
     * GET 请求通常无 Body，由调用方自行保证。
     */
    @Pattern(regexp = "GET|POST|PUT|PATCH", message = "httpMethod 只支持 GET/POST/PUT/PATCH")
    private String httpMethod = "POST";

    /** 自定义请求 Headers（可选） */
    private Map<String, String> headers;

    /** 请求 Body（可选，GET 时忽略） */
    private String body;

    /** 最大重试次数（可选，默认 5，最大 10） */
    @Positive(message = "maxRetry 必须为正整数")
    private Integer maxRetry = 5;

    // ---- Getters / Setters ----

    public String getTargetUrl() { return targetUrl; }
    public void setTargetUrl(String targetUrl) { this.targetUrl = targetUrl; }

    public String getHttpMethod() { return httpMethod; }
    public void setHttpMethod(String httpMethod) { this.httpMethod = httpMethod; }

    public Map<String, String> getHeaders() { return headers; }
    public void setHeaders(Map<String, String> headers) { this.headers = headers; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public Integer getMaxRetry() { return maxRetry; }
    public void setMaxRetry(Integer maxRetry) { this.maxRetry = maxRetry; }
}

