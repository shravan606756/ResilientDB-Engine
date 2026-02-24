package com.shravan.resilientdb_engine.backup.service;

import com.shravan.resilientdb_engine.backup.entity.DatabaseConfig;
import com.shravan.resilientdb_engine.backup.repository.DatabaseConfigRepository;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class DatabaseConfigService {

    private final DatabaseConfigRepository repository;

    public DatabaseConfigService(DatabaseConfigRepository repository) {
        this.repository = repository;
    }

    public DatabaseConfig registerDatabase(DatabaseConfig config) {
        return repository.save(config);
    }

    public List<DatabaseConfig> getAll() {
        return repository.findAll();
    }


    public DatabaseConfig getById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new RuntimeException("Database Configuration not found with ID: " + id));
    }
}