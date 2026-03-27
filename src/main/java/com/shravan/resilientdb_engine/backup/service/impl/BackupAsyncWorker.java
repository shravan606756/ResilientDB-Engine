package com.shravan.resilientdb_engine.backup.service.impl;

import com.shravan.resilientdb_engine.backup.entity.BackupJob;
import com.shravan.resilientdb_engine.backup.entity.BackupStatus;
import com.shravan.resilientdb_engine.backup.repository.BackupJobRepository;
import com.shravan.resilientdb_engine.backup.strategy.BackupStrategy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class
BackupAsyncWorker {

    private final BackupJobRepository backupJobRepository;
    private final ScheduledExecutorService timeoutMonitor;

    // If the async worker starts before the job row becomes visible to its read transaction,
    // we retry briefly to avoid leaving the job stuck in PENDING.
    private static final int JOB_NOT_FOUND_MAX_ATTEMPTS = 10;
    private static final long JOB_NOT_FOUND_SLEEP_MS = 200;

    public BackupAsyncWorker(BackupJobRepository backupJobRepository) {
        this.backupJobRepository = backupJobRepository;
        this.timeoutMonitor = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "BackupTimeoutMonitor");
            t.setDaemon(true);
            return t;
        });
    }

    @Async("backupTaskExecutor")
    public void performBackupAsync(Long jobId, BackupStrategy strategy) {
        System.out.println("=== [ASYNC WORKER] Thread " + Thread.currentThread().getName() + " picked up Job #" + jobId);

        BackupJob job = fetchJobWithRetry(jobId);
        if (job == null) {
            System.err.println("=== [ASYNC ERROR] Job #" + jobId + " not found in database!");
            return;
        }

        System.out.println("=== [ASYNC WORKER] Updating Job #" + jobId + " to IN_PROGRESS...");
        job.setStatus(BackupStatus.IN_PROGRESS);
        job.setStartedAt(LocalDateTime.now());
        backupJobRepository.saveAndFlush(job);
        System.out.println("=== [ASYNC WORKER] Job #" + jobId + " status saved as: " + job.getStatus());

        boolean success = false;

        while (job.getRetryCount() < job.getMaxRetries() && !success) {
            try {
                System.out.println("=== [ASYNC WORKER] Attempt #" + (job.getRetryCount() + 1) + " for Job #" + jobId);

                executeBackupWithTimeout(job, strategy, 30, TimeUnit.SECONDS);

                success = true;
                job.setStatus(BackupStatus.COMPLETED);
                job.setCompletedAt(LocalDateTime.now());
                job.setErrorMessage(null);
                System.out.println("=== [ASYNC WORKER] Job #" + jobId + " COMPLETED successfully!");

            } catch (TimeoutException e) {
                job.setStatus(BackupStatus.FAILED);
                job.setErrorMessage("Hard Timeout: Database backup process hung for >30 seconds.");
                backupJobRepository.saveAndFlush(job);
                System.err.println("=== [ASYNC ERROR] Job #" + jobId + " timed out and was killed.");
                return;

            } catch (Exception e) {
                job.setRetryCount(job.getRetryCount() + 1);
                System.err.println("=== [ASYNC ERROR] Job #" + jobId + " failed: " + e.getMessage());

                if (job.getRetryCount() >= job.getMaxRetries()) {
                    job.setStatus(BackupStatus.FAILED);
                    job.setErrorMessage("Failed after " + job.getRetryCount() + " attempts: " + e.getMessage());
                    System.err.println("=== [ASYNC WORKER] Job #" + jobId + " FAILED permanently.");
                } else {
                    System.out.println("=== [ASYNC WORKER] Retrying Job #" + jobId + " in 5 seconds...");
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } finally {
                backupJobRepository.saveAndFlush(job);
            }
        }

        cleanupOldBackups();
    }

    private BackupJob fetchJobWithRetry(Long jobId) {
        for (int attempt = 1; attempt <= JOB_NOT_FOUND_MAX_ATTEMPTS; attempt++) {
            BackupJob job = backupJobRepository.findById(jobId).orElse(null);
            if (job != null) {
                if (attempt > 1) {
                    System.err.println("=== [ASYNC WORKER] Job #" + jobId + " became visible after attempt " + attempt);
                }
                return job;
            }

            System.err.println("=== [ASYNC WORKER] Job #" + jobId + " not visible yet (attempt " + attempt + "/" + JOB_NOT_FOUND_MAX_ATTEMPTS + ")...");
            if (attempt < JOB_NOT_FOUND_MAX_ATTEMPTS) {
                try {
                    Thread.sleep(JOB_NOT_FOUND_SLEEP_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    private void executeBackupWithTimeout(BackupJob job, BackupStrategy strategy,
                                          long timeout, TimeUnit unit)
            throws TimeoutException, Exception {

        final CompletableFuture<Void> backupFuture = new CompletableFuture<>();
        final BackupStrategy.ProcessHolder processHolder = new BackupStrategy.ProcessHolder();

        Thread backupThread = new Thread(() -> {
            try {
                strategy.execute(job, processHolder);
                backupFuture.complete(null);
            } catch (Exception e) {
                backupFuture.completeExceptionally(e);
            }
        }, "BackupExec-Job" + job.getId());

        backupThread.setDaemon(true);
        backupThread.start();

        ScheduledFuture<?> timeoutTask = timeoutMonitor.schedule(() -> {
            if (!backupFuture.isDone()) {
                Process osProcess = processHolder.getProcess();
                if (osProcess != null && osProcess.isAlive()) {
                    osProcess.destroyForcibly();
                    try {
                        osProcess.waitFor(2, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                backupThread.interrupt();
                backupFuture.completeExceptionally(new TimeoutException("Backup exceeded " + timeout + " " + unit));
            }
        }, timeout, unit);

        try {
            backupFuture.get();
            timeoutTask.cancel(false);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof TimeoutException timeoutException) {
                throw timeoutException;
            } else if (cause instanceof Exception exception) {
                throw exception;
            } else {
                throw new Exception("Backup failed: " + cause.getMessage(), cause);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Exception("Backup was interrupted", e);
        } finally {
            timeoutTask.cancel(false);
        }
    }

    private void cleanupOldBackups() {
        try {
            java.nio.file.Path backupDir = java.nio.file.Paths.get("backups");
            if (!java.nio.file.Files.exists(backupDir)) return;

            List<java.nio.file.Path> files = java.nio.file.Files.list(backupDir)
                    .sorted((a, b) -> Long.compare(a.toFile().lastModified(), b.toFile().lastModified()))
                    .toList();

            if (files.size() > 5) {
                for (int i = 0; i < files.size() - 5; i++) {
                    java.nio.file.Files.deleteIfExists(files.get(i));
                    System.out.println("Cleaned up old backup: " + files.get(i).getFileName());
                }
            }
        } catch (Exception e) {
            System.err.println("Cleanup failed: " + e.getMessage());
        }
    }
}
