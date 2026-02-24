package com.shravan.resilientdb_engine.backup.strategy;

import com.shravan.resilientdb_engine.backup.entity.BackupJob;
import com.shravan.resilientdb_engine.backup.entity.DatabaseType;

public interface BackupStrategy {

    DatabaseType getSupportedType();

    // Renamed from 'executeBackup' to 'execute'
    void execute(BackupJob job);
}