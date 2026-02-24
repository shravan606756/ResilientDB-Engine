package com.shravan.resilientdb_engine.backup.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * PHASE 1 LOCKED: Entity for storing database connection configurations.
 * Added Lifecycle hooks to support Phase 5 standardized responses.
 */
@Entity
@Table(name = "database_configs")
public class DatabaseConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DatabaseType databaseType;

    @Column(nullable = false)
    private String host;

    @Column(nullable = false)
    private Integer port;

    @Column(nullable = false)
    private String dbName;

    @Column(nullable = false)
    private String username;

    @Column(nullable = false)
    private String password;

    // Added for Phase 5: Standardized Responses
    private LocalDateTime createdAt;

    public DatabaseConfig() {
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public DatabaseType getDatabaseType() { return databaseType; }
    public void setDatabaseType(DatabaseType databaseType) { this.databaseType = databaseType; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }

    public String getDbName() { return dbName; }
    public void setDbName(String dbName) { this.dbName = dbName; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    @Override
    public String toString() {
        return "DatabaseConfig{" +
                "id=" + id +
                ", databaseType=" + databaseType +
                ", host='" + host + '\'' +
                ", port=" + port +
                ", dbName='" + dbName + '\'' +
                ", username='" + username + '\'' +
                '}';
    }
}