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

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class BackupServiceImpl implements BackupService {

    private final BackupJobRepository backupJobRepository;
    private final DatabaseConfigService databaseConfigService;
    private final Map<String, BackupStrategy> backupStrategies;
    private final BackupServiceImpl self;

    public BackupServiceImpl(BackupJobRepository backupJobRepository,
                             DatabaseConfigService databaseConfigService,
                             Map<String, BackupStrategy> backupStrategies,
                             @Lazy BackupServiceImpl self) {
        this.backupJobRepository = backupJobRepository;
        this.databaseConfigService = databaseConfigService;
        this.backupStrategies = backupStrategies;
        this.self = self;
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

        // Save and Flush ensures the ID is generated before the Async thread starts
        BackupJob savedJob = backupJobRepository.saveAndFlush(job);

        BackupStrategy strategy = backupStrategies.get(config.getDatabaseType().name());
        if (strategy != null) {
            // Using ID ensures the Async thread can fetch a fresh, attached entity
            self.performBackupAsync(savedJob.getId(), strategy);
        } else {
            throw new RuntimeException("No strategy found for type: " + config.getDatabaseType());
        }

        return savedJob;
    }

    @Async
    public void performBackupAsync(Long jobId, BackupStrategy strategy) {
        BackupJob job = backupJobRepository.findById(jobId).orElse(null);
        if (job == null) return;

        job.setStatus(BackupStatus.IN_PROGRESS);
        job.setStartedAt(LocalDateTime.now());
        backupJobRepository.saveAndFlush(job);

        boolean success = false;

        while (job.getRetryCount() < job.getMaxRetries() && !success) {
            try {
                // REAL LOGIC: Execute the actual database dump strategy
                strategy.execute(job);

                // If the strategy finishes without throwing an exception, it's a success!
                success = true;

                job.setStatus(BackupStatus.COMPLETED);
                job.setCompletedAt(LocalDateTime.now());

            } catch (Exception e) {
                // If strategy.execute() throws an error (e.g., bad password, DB offline), we catch it here.
                job.setRetryCount(job.getRetryCount() + 1);

                if (job.getRetryCount() >= job.getMaxRetries()) {
                    job.setStatus(BackupStatus.FAILED);
                    job.setErrorMessage("Failed after " + job.getRetryCount() + " attempts: " + e.getMessage());
                    System.err.println(">>> Backup Job " + jobId + " FAILED permanently.");
                } else {
                    System.out.println(">>> Attempt " + job.getRetryCount() + " failed for Job " + jobId + ". Retrying in 5 seconds...");

                    // REAL-WORLD BACKOFF: Wait 5 seconds before hitting the database again
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } finally {
                // Save the current state (either a retry update, completion, or final failure)
                backupJobRepository.saveAndFlush(job);
            }
        }

        cleanupOldBackups();
    }

    // --- Required Interface Methods ---

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
        return new FileSystemResource(Paths.get(job.getFilePath()));
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

            // Keep only the 5 most recent files
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

        // 1. Clean up the physical file if it exists
        if (job.getFilePath() != null) {
            try {
                java.nio.file.Path path = java.nio.file.Paths.get(job.getFilePath());
                boolean deleted = java.nio.file.Files.deleteIfExists(path);
                if (deleted) {
                    System.out.println("Deleted physical backup file: " + job.getFilePath());
                }
            } catch (Exception e) {
                System.err.println("Failed to delete physical file: " + e.getMessage());
                // We catch the error but don't throw it, so the DB record can still be deleted!
            }
        }

        // 2. Remove the record from the database
        backupJobRepository.delete(job);
        System.out.println("Deleted database record for Job ID: " + id);
    }
}