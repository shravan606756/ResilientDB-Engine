package com.shravan.resilientdb_engine.backup.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Health check endpoint to monitor the async executor status
 * Useful for debugging stuck PENDING jobs
 */
@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/api/health")
public class HealthCheckController {

    private final ThreadPoolTaskExecutor backupTaskExecutor;

    public HealthCheckController(ThreadPoolTaskExecutor backupTaskExecutor) {
        this.backupTaskExecutor = backupTaskExecutor;
    }

    /**
     * GET /api/health/executor
     *
     * Returns current thread pool statistics to help diagnose stuck jobs
     *
     * Example response:
     * {
     *   "active_threads": 2,
     *   "pool_size": 5,
     *   "core_pool_size": 5,
     *   "max_pool_size": 10,
     *   "queue_size": 0,
     *   "queue_capacity": 25,
     *   "remaining_queue_capacity": 25,
     *   "completed_task_count": 47,
     *   "status": "healthy"
     * }
     */
    @GetMapping("/executor")
    public ResponseEntity<Map<String, Object>> getExecutorHealth() {
        Map<String, Object> health = new HashMap<>();

        try {
            // Current state
            health.put("active_threads", backupTaskExecutor.getActiveCount());
            health.put("pool_size", backupTaskExecutor.getPoolSize());
            health.put("core_pool_size", backupTaskExecutor.getCorePoolSize());
            health.put("max_pool_size", backupTaskExecutor.getMaxPoolSize());

            // Queue information
            int queueSize = backupTaskExecutor.getThreadPoolExecutor().getQueue().size();
            int queueCapacity = backupTaskExecutor.getQueueCapacity();
            int remainingCapacity = backupTaskExecutor.getThreadPoolExecutor().getQueue().remainingCapacity();

            health.put("queue_size", queueSize);
            health.put("queue_capacity", queueCapacity);
            health.put("remaining_queue_capacity", remainingCapacity);

            // Historical data
            health.put("completed_task_count", backupTaskExecutor.getThreadPoolExecutor().getCompletedTaskCount());
            health.put("total_task_count", backupTaskExecutor.getThreadPoolExecutor().getTaskCount());

            // Health status
            String status = "healthy";
            if (queueSize >= queueCapacity * 0.9) {
                status = "degraded"; // Queue is almost full
            }
            if (remainingCapacity == 0) {
                status = "critical"; // Queue is completely full
            }

            health.put("status", status);

            // Add diagnostic message
            if (backupTaskExecutor.getActiveCount() == 0 && queueSize == 0) {
                health.put("message", "Executor is idle - ready to process jobs");
            } else if (backupTaskExecutor.getActiveCount() > 0) {
                health.put("message", "Currently processing " + backupTaskExecutor.getActiveCount() + " jobs");
            } else if (queueSize > 0) {
                health.put("message", "Warning: " + queueSize + " jobs waiting in queue");
            }

            return ResponseEntity.ok(health);

        } catch (Exception e) {
            health.put("status", "error");
            health.put("error", e.getMessage());
            return ResponseEntity.status(500).body(health);
        }
    }

    /**
     * GET /api/health/ping
     *
     * Simple health check endpoint
     */
    @GetMapping("/ping")
    public ResponseEntity<Map<String, String>> ping() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "ok");
        response.put("service", "ResilientDB Backup Engine");
        response.put("timestamp", java.time.LocalDateTime.now().toString());
        return ResponseEntity.ok(response);
    }
}