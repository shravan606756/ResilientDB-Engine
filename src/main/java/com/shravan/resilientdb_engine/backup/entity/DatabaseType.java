package com.shravan.resilientdb_engine.backup.entity;
/**
 * Supported database types for backup operations.
 */
public enum DatabaseType {
    MYSQL,
    POSTGRESQL,
    MONGODB,
    REDIS,
    ORACLE,
    OTHERS,
    UNKNOWN
}
