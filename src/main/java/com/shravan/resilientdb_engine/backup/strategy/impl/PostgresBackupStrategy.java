package com.shravan.resilientdb_engine.backup.strategy.impl;

import com.shravan.resilientdb_engine.backup.entity.BackupJob;
import com.shravan.resilientdb_engine.backup.entity.DatabaseConfig;
import com.shravan.resilientdb_engine.backup.entity.DatabaseType;
import com.shravan.resilientdb_engine.backup.strategy.BackupStrategy;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component("POSTGRESQL")
public class PostgresBackupStrategy implements BackupStrategy {

    @Override
    public DatabaseType getSupportedType() {
        return DatabaseType.POSTGRESQL;
    }

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

            // Inject password into the environment variables
            pb.environment().put("PGPASSWORD", config.getPassword());

            // Merge standard error and standard output
            pb.redirectErrorStream(true);

            Process process = pb.start();

            // --- THE FIX: Consume the output to prevent OS buffer deadlock ---
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // This prints the real-time pg_dump logs to your Spring Boot terminal!
                    System.out.println("[pg_dump] " + line);
                }
            }
            // -----------------------------------------------------------------

            // Wait for the process to finish only AFTER the stream is fully read
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                throw new RuntimeException("pg_dump failed with exit code: " + exitCode);
            }

            File backupFile = new File(filePath);

            // Also adding a length check to ensure the file isn't empty
            if (backupFile.exists() && backupFile.length() > 0) {
                job.setFilePath(filePath);
                job.setSizeBytes(backupFile.length());
            } else {
                throw new RuntimeException("Backup command succeeded but no valid file was generated.");
            }

        } catch (Exception e) {
            throw new RuntimeException("PostgreSQL strategy failed: " + e.getMessage(), e);
        }
    }
}