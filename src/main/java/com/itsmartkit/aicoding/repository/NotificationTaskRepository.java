package com.itsmartkit.aicoding.repository;

import com.itsmartkit.aicoding.domain.NotificationTask;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface NotificationTaskRepository extends JpaRepository<NotificationTask, Long> {

    /**
     * 查询重试耗尽的任务（EXHAUSTED），供告警调度器使用。
     * 使用 Pageable 限制数量，兼容 MySQL / PostgreSQL 等各类数据库方言。
     */
    @Query("SELECT t FROM NotificationTask t WHERE t.status = 'EXHAUSTED' ORDER BY t.createdAt ASC")
    List<NotificationTask> findExhaustedTasks(Pageable pageable);
}

