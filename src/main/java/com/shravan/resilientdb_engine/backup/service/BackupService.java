package com.shravan.resilientdb_engine.backup.service;

import com.shravan.resilientdb_engine.backup.dto.CreateBackupRequest;
import com.shravan.resilientdb_engine.backup.entity.BackupJob;
import com.shravan.resilientdb_engine.backup.entity.BackupStatus;
import org.springframework.core.io.Resource;

import java.util.List;

public interface BackupService {

    /**
     * Triggers a new backup operation based on the provided configuration.
     * Replaces the old file upload mechanism.
     */
    BackupJob triggerBackup(CreateBackupRequest request);

    // ----------------------------------------------------------------
    // EXISTING: Read & Download Operations
    // ----------------------------------------------------------------

    BackupJob getBackupJobById(Long id);

    List<BackupJob> getAllBackupJobs();

    List<BackupJob> getBackupJobsByStatus(BackupStatus status);

    BackupJob updateBackupStatus(Long id, BackupStatus status);

    List<BackupJob> getLatestBackups();

    Resource downloadBackup(Long id);

    // --- NEW: Smart Delete Operation ---
    void deleteBackupJob(Long id);
}