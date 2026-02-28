package com.shravan.resilientdb_engine.backup.service.impl;

import com.shravan.resilientdb_engine.backup.dto.CreateBackupRequest;
import com.shravan.resilientdb_engine.backup.entity.BackupJob;
import com.shravan.resilientdb_engine.backup.entity.BackupStatus;
import com.shravan.resilientdb_engine.backup.entity.DatabaseConfig;
import com.shravan.resilientdb_engine.backup.repository.BackupJobRepository;
import com.shravan.resilientdb_engine.backup.service.BackupService;
import com.shravan.resilientdb_engine.backup.service.DatabaseConfigService;
import com.shravan.resilientdb_engine.backup.strategy.BackupStrategy;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Non-blocking implementation with CORRECT OS-level process cleanup.
 *
 * CRITICAL FIX: Uses ProcessHandle to track and kill actual pg_dump/mysqldump/mongodump
 * processes at the OS level, not just the Java thread wrapper.
 */
@Service
public class BackupServiceImpl implements BackupService {

    private final BackupJobRepository backupJobRepository;
    private final DatabaseConfigService databaseConfigService;
    private final Map<String, BackupStrategy> backupStrategies;
    private final BackupServiceImpl self;

    // Dedicated executor for timeout monitoring
    private final ScheduledExecutorService timeoutMonitor;

    public BackupServiceImpl(BackupJobRepository backupJobRepository,
                             DatabaseConfigService databaseConfigService,
                             Map<String, BackupStrategy> backupStrategies,
                             @Lazy BackupServiceImpl self) {
        this.backupJobRepository = backupJobRepository;
        this.databaseConfigService = databaseConfigService;
        this.backupStrategies = backupStrategies;
        this.self = self;

        this.timeoutMonitor = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "BackupTimeoutMonitor");
            t.setDaemon(true);
            return t;
        });

        System.out.println("=== [INIT] BackupServiceImpl initialized with non-blocking architecture");
    }

    @Override
    @Transactional
    public BackupJob triggerBackup(CreateBackupRequest request) {
        DatabaseConfig config = databaseConfigService.getById(request.getDatabaseConfigId());

        BackupJob job = new BackupJob();
        job.setJobName(request.getJobName());
        job.setDatabaseType(config.getDatabaseType());
        job.setDatabaseConfig(config);
        job.setStatus(BackupStatus.PENDING);
        job.setMaxRetries(3);

        BackupJob savedJob = backupJobRepository.saveAndFlush(job);

        System.out.println("=== [TRIGGER] Job #" + savedJob.getId() + " created with status: " + savedJob.getStatus());

        BackupStrategy strategy = backupStrategies.get(config.getDatabaseType().name());
        if (strategy != null) {
            System.out.println("=== [TRIGGER] Delegating Job #" + savedJob.getId() + " to async worker...");
            self.performBackupAsync(savedJob.getId(), strategy);
        } else {
            throw new RuntimeException("No strategy found for type: " + config.getDatabaseType());
        }

        return savedJob;
    }

    @Async("backupTaskExecutor")
    @Transactional
    public void performBackupAsync(Long jobId, BackupStrategy strategy) {
        System.out.println("=== [ASYNC WORKER] Thread " + Thread.currentThread().getName() + " picked up Job #" + jobId);

        BackupJob job = backupJobRepository.findById(jobId).orElse(null);
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

    /**
     * ✅ CORRECTED NON-BLOCKING BACKUP EXECUTION WITH PROPER OS-LEVEL PROCESS CLEANUP
     *
     * Key improvements over the previous version:
     * 1. No deprecated Thread.stop() method
     * 2. Tracks the actual OS process (pg_dump.exe, mysqldump.exe, etc.)
     * 3. Kills ONLY this job's process, not all database processes on the system
     * 4. Works correctly on both Windows and Linux
     *
     * Strategy Modification Required:
     * - Your BackupStrategy.execute() methods must be modified to accept and store
     *   the Process object so we can kill it later
     * - See updated strategy implementations below
     */
    private void executeBackupWithTimeout(BackupJob job, BackupStrategy strategy,
                                          long timeout, TimeUnit unit)
            throws TimeoutException, Exception {

        final CompletableFuture<Void> backupFuture = new CompletableFuture<>();

        // Holder for the actual OS process (pg_dump.exe, mysqldump.exe, etc.)
        final BackupStrategy.ProcessHolder processHolder = new BackupStrategy.ProcessHolder();

        Thread backupThread = new Thread(() -> {
            try {
                System.out.println("=== [BACKUP EXEC] Thread " + Thread.currentThread().getName() + " executing strategy for Job #" + job.getId());

                // ✅ CRITICAL: Strategy must store the Process in processHolder
                // This allows us to kill the actual OS process later
                strategy.execute(job, processHolder);

                backupFuture.complete(null);
                System.out.println("=== [BACKUP EXEC] Strategy completed for Job #" + job.getId());
            } catch (Exception e) {
                System.err.println("=== [BACKUP EXEC] Strategy failed for Job #" + job.getId() + ": " + e.getMessage());
                backupFuture.completeExceptionally(e);
            }
        }, "BackupExec-Job" + job.getId());

        backupThread.setDaemon(true);
        backupThread.start();

        // ✅ CORRECTED TIMEOUT HANDLER - No deprecated methods, kills actual OS process
        ScheduledFuture<?> timeoutTask = timeoutMonitor.schedule(() -> {
            if (!backupFuture.isDone()) {
                System.err.println("=== [TIMEOUT MONITOR] Job #" + job.getId() + " exceeded " + timeout + " " + unit);

                // 1. Get the actual OS process (pg_dump.exe, mysqldump.exe, etc.)
                Process osProcess = processHolder.getProcess();

                if (osProcess != null && osProcess.isAlive()) {
                    System.err.println("=== [TIMEOUT MONITOR] Killing OS process for Job #" + job.getId() + "...");

                    // ✅ Kill the actual OS process first (this is the key fix!)
                    osProcess.destroyForcibly();

                    // Wait up to 2 seconds for process to die
                    try {
                        boolean terminated = osProcess.waitFor(2, TimeUnit.SECONDS);
                        if (terminated) {
                            System.err.println("=== [TIMEOUT MONITOR] OS process killed successfully for Job #" + job.getId());
                        } else {
                            System.err.println("=== [TIMEOUT MONITOR] WARNING: OS process did not terminate for Job #" + job.getId());
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                } else {
                    System.err.println("=== [TIMEOUT MONITOR] No OS process to kill for Job #" + job.getId());
                }

                // 2. Now interrupt the Java thread (safe, no deprecated methods)
                backupThread.interrupt();

                // 3. Complete the future with timeout exception
                backupFuture.completeExceptionally(new TimeoutException("Backup exceeded " + timeout + " " + unit));
            }
        }, timeout, unit);

        try {
            backupFuture.get();
            timeoutTask.cancel(false);

        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof TimeoutException) {
                throw (TimeoutException) cause;
            } else if (cause instanceof Exception) {
                throw (Exception) cause;
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

    /**
     * Simple holder class to pass Process reference between threads.
     * This allows the timeout monitor to access the actual OS process.
     */


    // --- Required Interface Methods (unchanged) ---

    @Override
    public List<BackupJob> getBackupJobsByStatus(BackupStatus status) {
        return backupJobRepository.findByStatus(status);
    }

    @Override
    public BackupJob getBackupJobById(Long id) {
        return backupJobRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Backup job not found with id: " + id));
    }

    @Override
    public List<BackupJob> getAllBackupJobs() {
        return backupJobRepository.findAll();
    }

    @Override
    public List<BackupJob> getLatestBackups() {
        return backupJobRepository.findTop10ByOrderByCreatedAtDesc();
    }

    @Override
    public Resource downloadBackup(Long id) {
        BackupJob job = getBackupJobById(id);
        if (job.getFilePath() == null) {
            throw new RuntimeException("No file available for download for job ID: " + id);
        }
        return new FileSystemResource(java.nio.file.Paths.get(job.getFilePath()));
    }

    @Override
    @Transactional
    public BackupJob updateBackupStatus(Long id, BackupStatus status) {
        BackupJob job = getBackupJobById(id);
        job.setStatus(status);
        return backupJobRepository.save(job);
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

    @Override
    @Transactional
    public void deleteBackupJob(Long id) {
        BackupJob job = getBackupJobById(id);

        if (job.getFilePath() != null) {
            try {
                java.nio.file.Path path = java.nio.file.Paths.get(job.getFilePath());
                boolean deleted = java.nio.file.Files.deleteIfExists(path);
                if (deleted) {
                    System.out.println("Deleted physical backup file: " + job.getFilePath());
                }
            } catch (Exception e) {
                System.err.println("Failed to delete physical file: " + e.getMessage());
            }
        }

        backupJobRepository.delete(job);
        System.out.println("Deleted database record for Job ID: " + id);
    }
}