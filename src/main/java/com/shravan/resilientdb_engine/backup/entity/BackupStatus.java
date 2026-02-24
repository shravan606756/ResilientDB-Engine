package com.shravan.resilientdb_engine.backup.entity;
/**
 * Represents the various states of a backup job lifecycle.
 */
public enum BackupStatus {
    /**
     * The backup job has been created but not yet started.
     */
    PENDING,
    /**
     * The backup process is currently running.
     */
    IN_PROGRESS,
    /**
     * The backup completed successfully.
     */
    COMPLETED,
    /**
     * The backup failed due to an error.
     */
    FAILED,
    /**
     * The backup was manually cancelled.
     */
    CANCELLED
}