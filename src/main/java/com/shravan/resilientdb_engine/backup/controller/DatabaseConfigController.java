package com.shravan.resilientdb_engine.backup.controller;
import com.shravan.resilientdb_engine.backup.entity.DatabaseConfig;
import com.shravan.resilientdb_engine.backup.service.DatabaseConfigService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
/**
 * Controller for managing database configurations.
 * Kept simple as per current requirements (No DTOs, direct Entity return).
 */
@RestController
@RequestMapping("/api/databases")
public class DatabaseConfigController {
    private final DatabaseConfigService databaseConfigService;
    public DatabaseConfigController(DatabaseConfigService databaseConfigService) {
        this.databaseConfigService = databaseConfigService;
    }
    /**
     * Registers a new database configuration.
     * POST /api/databases
     */
    @PostMapping
    public ResponseEntity<DatabaseConfig> registerDatabase(@RequestBody DatabaseConfig databaseConfig) {
        DatabaseConfig savedConfig = databaseConfigService.registerDatabase(databaseConfig);
        return new ResponseEntity<>(savedConfig, HttpStatus.CREATED);
    }
    /**
     * Retrieves all registered database configurations.
     * GET /api/databases
     */
    @GetMapping
    public ResponseEntity<java.util.List<DatabaseConfig>> getAllDatabases() {
        java.util.List<DatabaseConfig> configs = databaseConfigService.getAll();
        return new ResponseEntity<>(configs, HttpStatus.OK);
    }
}