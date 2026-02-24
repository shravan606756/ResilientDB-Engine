package com.shravan.resilientdb_engine.backup.strategy.impl;

import com.shravan.resilientdb_engine.backup.entity.BackupJob;
import com.shravan.resilientdb_engine.backup.entity.DatabaseConfig;
import com.shravan.resilientdb_engine.backup.entity.DatabaseType; // Added import
import com.shravan.resilientdb_engine.backup.strategy.BackupStrategy;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component("POSTGRESQL")
public class PostgresBackupStrategy implements BackupStrategy {

    // --- FIX 1: Implement the required interface method ---
    @Override
    public DatabaseType getSupportedType() {
        return DatabaseType.POSTGRESQL;
    }

    // --- FIX 2: Remove 'throws Exception' from the signature ---
    @Override
    public void execute(BackupJob job) {
        try {
            DatabaseConfig config = job.getDatabaseConfig();

            File backupDir = new File("backups");
            if (!backupDir.exists()) backupDir.mkdirs();

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            String fileName = "backup_pg_" + config.getDbName() + "_" + timestamp + ".sql";
            String filePath = backupDir.getAbsolutePath() + File.separator + fileName;

            ProcessBuilder pb = new ProcessBuilder(
                    "pg_dump",
                    "-h", config.getHost(),
                    "-p", String.valueOf(config.getPort()),
                    "-U", config.getUsername(),
                    "--no-password", // Force it to use the environment variable
                    "-v",            // Verbose mode for better logging
                    "-f", filePath,
                    config.getDbName()
            );

            pb.environment().put("PGPASSWORD", config.getPassword());
            pb.redirectErrorStream(true);

            Process process = pb.start();
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                throw new RuntimeException("pg_dump failed with exit code: " + exitCode);
            }

            File backupFile = new File(filePath);
            if (backupFile.exists()) {
                job.setFilePath(filePath);
                job.setSizeBytes(backupFile.length());
            } else {
                throw new RuntimeException("Backup command succeeded but no file was generated.");
            }

        } catch (Exception e) {
            // FIX 2 (cont): Catch checked exceptions and throw RuntimeException to trigger the retry loop
            throw new RuntimeException("PostgreSQL strategy failed: " + e.getMessage(), e);
        }
    }
}