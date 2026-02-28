package com.shravan.resilientdb_engine.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "backupTaskExecutor")
    public ThreadPoolTaskExecutor backupTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // Thread pool sizing
        executor.setCorePoolSize(5);           // 5 workers always ready
        executor.setMaxPoolSize(10);          // Can scale if needed
        executor.setQueueCapacity(25);        // Jobs can wait here

        // Thread naming for debugging
        executor.setThreadNamePrefix("BackupWorker-");

        // CRITICAL FIX: Set rejection policy
        // CallerRunsPolicy means if the queue is full, the calling thread will run the task
        // This prevents tasks from being silently dropped
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // Allow core threads to timeout when idle (optional, saves resources)
        executor.setAllowCoreThreadTimeOut(true);
        executor.setKeepAliveSeconds(60);

        // Wait for tasks to complete on shutdown
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);

        // Initialize the executor
        executor.initialize();

        System.out.println("=== [ASYNC CONFIG] BackupTaskExecutor initialized with:");
        System.out.println("    - Core Pool Size: " + executor.getCorePoolSize());
        System.out.println("    - Max Pool Size: " + executor.getMaxPoolSize());
        System.out.println("    - Queue Capacity: " + executor.getQueueCapacity());

        return executor;
    }
}