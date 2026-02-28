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

@Component("MONGODB")
public class MongoBackupStrategy implements BackupStrategy {

    @Override
    public DatabaseType getSupportedType() {
        return DatabaseType.MONGODB;
    }

    /**
     * ✅ UPDATED: Now accepts ProcessHolder to allow OS-level process cleanup on timeout
     */
    @Override
    public void execute(BackupJob job, BackupStrategy.ProcessHolder processHolder) {
        try {
            DatabaseConfig config = job.getDatabaseConfig();

            File backupDir = new File("backups");
            if (!backupDir.exists()) {
                backupDir.mkdirs();
            }

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            String fileName = "backup_mongo_" + config.getDbName() + "_" + timestamp + ".archive";
            String filePath = backupDir.getAbsolutePath() + File.separator + fileName;

            String connectionString = String.format("mongodb://%s:%s@%s:%d/%s?authSource=admin",
                    config.getUsername(),
                    config.getPassword(),
                    config.getHost(),
                    config.getPort(),
                    config.getDbName());

            ProcessBuilder pb = new ProcessBuilder(
                    "mongodump",
                    "--uri", connectionString,
                    "--archive=" + filePath,
                    "--gzip"
            );

            pb.redirectErrorStream(true);

            Process process = pb.start();

            // ✅ CRITICAL FIX: Store the Process in the holder
            processHolder.setProcess(process);

            // Consume output to prevent deadlock
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("[mongodump] " + line);
                }
            }

            int exitCode = process.waitFor();

            if (exitCode != 0) {
                throw new RuntimeException("mongodump failed with exit code: " + exitCode);
            }

            File backupFile = new File(filePath);
            if (backupFile.exists() && backupFile.length() > 0) {
                job.setFilePath(filePath);
                job.setSizeBytes(backupFile.length());
            } else {
                throw new RuntimeException("Backup command succeeded but no file was generated.");
            }

        } catch (Exception e) {
            throw new RuntimeException("MongoDB strategy failed: " + e.getMessage(), e);
        }
    }
}