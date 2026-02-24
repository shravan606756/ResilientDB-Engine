package com.shravan.resilientdb_engine.backup.event;
import com.shravan.resilientdb_engine.backup.entity.BackupJob;
import com.shravan.resilientdb_engine.backup.entity.BackupStatus;
import org.springframework.context.ApplicationEvent;
/**
 * Event published when a backup job's status changes.
 */
public class BackupStatusChangedEvent extends ApplicationEvent {
    private final BackupJob backupJob;
    private final BackupStatus oldStatus;
    private final BackupStatus newStatus;
    public BackupStatusChangedEvent(Object source, BackupJob backupJob, BackupStatus oldStatus,
                                    BackupStatus newStatus) {
        super(source);
        this.backupJob = backupJob;
        this.oldStatus = oldStatus;
        this.newStatus = newStatus;
    }
    public BackupJob getBackupJob() {
        return backupJob;
    }
    public BackupStatus getOldStatus() {
        return oldStatus;
    }
    public BackupStatus getNewStatus() {
        return newStatus;
    }
}