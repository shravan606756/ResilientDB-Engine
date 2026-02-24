package com.shravan.resilientdb_engine.backup.dto;

/**
 * PHASE 1 LOCKED: Only accepts the Configuration ID and Job Name.
 * Replaced raw credentials with databaseConfigId for security and reusability.
 */
public class CreateBackupRequest {

    private Long databaseConfigId; // Link to registered DatabaseConfig
    private String jobName;        // Custom name for this specific backup run

    // --- Constructors ---

    public CreateBackupRequest() {
    }

    public CreateBackupRequest(Long databaseConfigId, String jobName) {
        this.databaseConfigId = databaseConfigId;
        this.jobName = jobName;
    }

    // --- Getters and Setters ---

    public Long getDatabaseConfigId() {
        return databaseConfigId;
    }

    public void setDatabaseConfigId(Long databaseConfigId) {
        this.databaseConfigId = databaseConfigId;
    }

    public String getJobName() {
        return jobName;
    }

    public void setJobName(String jobName) {
        this.jobName = jobName;
    }

    // --- ToString (For Logging) ---

    @Override
    public String toString() {
        return "CreateBackupRequest{" +
                "databaseConfigId=" + databaseConfigId +
                ", jobName='" + jobName + '\'' +
                '}';
    }
}