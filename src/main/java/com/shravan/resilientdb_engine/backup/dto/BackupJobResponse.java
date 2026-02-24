package com.shravan.resilientdb_engine.backup.dto;

import com.shravan.resilientdb_engine.backup.entity.BackupJob;
import com.shravan.resilientdb_engine.backup.entity.BackupStatus;
import com.shravan.resilientdb_engine.backup.entity.DatabaseType;
import java.time.LocalDateTime;

/**
 * Response DTO for BackupJob information.
 */
public class BackupJobResponse {
    private Long id;
    private String jobName;
    private BackupStatus status;
    private DatabaseType databaseType;
    private Long sizeBytes;
    private String errorMessage; // Added for Phase 2
    private int retryCount;      // Added for Phase 2
    private int maxRetries;      // Added for Phase 2
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    public BackupJobResponse(Long id, String jobName, BackupStatus status, DatabaseType databaseType,
                             Long sizeBytes, String errorMessage, int retryCount, int maxRetries,
                             LocalDateTime createdAt, LocalDateTime startedAt, LocalDateTime completedAt) {
        this.id = id;
        this.jobName = jobName;
        this.status = status;
        this.databaseType = databaseType;
        this.sizeBytes = sizeBytes;
        this.errorMessage = errorMessage;
        this.retryCount = retryCount;
        this.maxRetries = maxRetries;
        this.createdAt = createdAt;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
    }

    public static BackupJobResponse fromEntity(BackupJob job) {
        return new BackupJobResponse(
                job.getId(),
                job.getJobName(),
                job.getStatus(),
                job.getDatabaseType(),
                job.getSizeBytes(),
                job.getErrorMessage(), // Mapping new field
                job.getRetryCount(),   // Mapping new field
                job.getMaxRetries(),   // Mapping new field
                job.getCreatedAt(),
                job.getStartedAt(),
                job.getCompletedAt());
    }

    // --- GETTERS AND SETTERS ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getJobName() { return jobName; }
    public void setJobName(String jobName) { this.jobName = jobName; }

    public BackupStatus getStatus() { return status; }
    public void setStatus(BackupStatus status) { this.status = status; }

    public DatabaseType getDatabaseType() { return databaseType; }
    public void setDatabaseType(DatabaseType databaseType) { this.databaseType = databaseType; }

    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }

    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
}