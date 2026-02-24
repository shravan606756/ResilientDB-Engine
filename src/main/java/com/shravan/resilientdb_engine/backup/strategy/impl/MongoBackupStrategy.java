package com.shravan.resilientdb_engine.backup.strategy.impl;

import com.shravan.resilientdb_engine.backup.entity.BackupJob;
import com.shravan.resilientdb_engine.backup.entity.DatabaseConfig;
import com.shravan.resilientdb_engine.backup.entity.DatabaseType;
import com.shravan.resilientdb_engine.backup.strategy.BackupStrategy;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component("MONGODB")
public class MongoBackupStrategy implements BackupStrategy {

    @Override
    public DatabaseType getSupportedType() {
        return DatabaseType.MONGODB;
    }

    @Override
    public void execute(BackupJob job) {
        try {
            DatabaseConfig config = job.getDatabaseConfig();

            // Ensure the backup directory exists
            File backupDir = new File("backups");
            if (!backupDir.exists()) {
                backupDir.mkdirs();
            }

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            // .archive is the standard extension when using the --archive flag
            String fileName = "backup_mongo_" + config.getDbName() + "_" + timestamp + ".archive";
            String filePath = backupDir.getAbsolutePath() + File.separator + fileName;

            // Use the Connection String format for more robust authentication
            // Format: mongodb://user:pass@host:port/dbname?authSource=admin
            String connectionString = String.format("mongodb://%s:%s@%s:%d/%s?authSource=admin",
                    config.getUsername(),
                    config.getPassword(),
                    config.getHost(),
                    config.getPort(),
                    config.getDbName());

            // Build the mongodump command using the modern --uri flag
            ProcessBuilder pb = new ProcessBuilder(
                    "mongodump",
                    "--uri", connectionString,
                    "--archive=" + filePath,
                    "--gzip" // Compression is highly recommended for MongoDB JSON-like data
            );

            // Merge error stream with standard output so we can see why it fails in our logs
            pb.redirectErrorStream(true);

            Process process = pb.start();
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
            // Rethrow as RuntimeException to trigger the Retry loop in your BackupServiceImpl
            throw new RuntimeException("MongoDB strategy failed: " + e.getMessage(), e);
        }
    }
}