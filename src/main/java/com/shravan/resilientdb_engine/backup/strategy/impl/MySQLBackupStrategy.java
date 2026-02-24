package com.shravan.resilientdb_engine.backup.strategy.impl;

import com.shravan.resilientdb_engine.backup.entity.BackupJob;
import com.shravan.resilientdb_engine.backup.entity.DatabaseConfig;
import com.shravan.resilientdb_engine.backup.entity.DatabaseType;
import com.shravan.resilientdb_engine.backup.strategy.BackupStrategy;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component("MYSQL")
public class MySQLBackupStrategy implements BackupStrategy {

    @Override
    public DatabaseType getSupportedType() {
        return DatabaseType.MYSQL;
    }

    @Override
    public void execute(BackupJob job) {
        try {
            DatabaseConfig config = job.getDatabaseConfig();

            File backupDir = new File("backups");
            if (!backupDir.exists()) backupDir.mkdirs();

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            String fileName = "backup_mysql_" + config.getDbName() + "_" + timestamp + ".sql";
            String filePath = backupDir.getAbsolutePath() + File.separator + fileName;

            // Build the mysqldump command
            // inside MySQLBackupStrategy.java execute()
            ProcessBuilder pb = new ProcessBuilder(
                    "mysqldump",
                    "--no-defaults", // Prevent reading /etc/my.cnf inside container
                    "-h", config.getHost(),
                    "-P", String.valueOf(config.getPort()),
                    "-u", config.getUsername(),
                    "--column-statistics=0", // Fix for MySQL 8+ compatibility
                    "--result-file=" + filePath,
                    config.getDbName()
            );

            pb.environment().put("MYSQL_PWD", config.getPassword());

            // Securely set the password in the environment variables
            pb.environment().put("MYSQL_PWD", config.getPassword());
            pb.redirectErrorStream(true);

            Process process = pb.start();
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                throw new RuntimeException("mysqldump failed with exit code: " + exitCode);
            }

            File backupFile = new File(filePath);
            if (backupFile.exists() && backupFile.length() > 0) {
                job.setFilePath(filePath);
                job.setSizeBytes(backupFile.length());
            } else {
                throw new RuntimeException("Backup command succeeded but no file was generated.");
            }

        } catch (Exception e) {
            // Catch checked exceptions and throw RuntimeException to trigger your BackupServiceImpl retry loop
            throw new RuntimeException("MySQL strategy failed: " + e.getMessage(), e);
        }
    }
}