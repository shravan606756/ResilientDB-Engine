package com.shravan.resilientdb_engine.backup.repository;
import com.shravan.resilientdb_engine.backup.entity.BackupJob;
import com.shravan.resilientdb_engine.backup.entity.BackupStatus;
import com.shravan.resilientdb_engine.backup.entity.DatabaseType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
/**
 * Repository for managing BackupJob entities.
 */
@Repository
public interface BackupJobRepository extends JpaRepository<BackupJob, Long> {
    /**
     * Find backup jobs by their status.
     *
     * @param status the status to filter by
     * @return list of backup jobs with the given status
     */
    List<BackupJob> findByStatus(BackupStatus status);
    /**
     * Find backup jobs by database type.
     *
     * @param databaseType the database type to filter by
     * @return list of backup jobs for the given database type
     */
    List<BackupJob> findByDatabaseType(DatabaseType databaseType);
    /**
     * Find the top 10 most recent backup jobs.
     *
     * @return list of the 10 most recent backup jobs
     */
    List<BackupJob> findTop10ByOrderByCreatedAtDesc();
}