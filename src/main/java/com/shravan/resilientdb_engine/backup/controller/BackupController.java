package com.shravan.resilientdb_engine.backup.controller;

import com.shravan.resilientdb_engine.backup.dto.BackupJobResponse;
import com.shravan.resilientdb_engine.backup.dto.CreateBackupRequest;
import com.shravan.resilientdb_engine.backup.entity.BackupJob;
import com.shravan.resilientdb_engine.backup.entity.BackupStatus;
import com.shravan.resilientdb_engine.backup.service.BackupService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.List;
import java.util.stream.Collectors;

/**
 * REST Controller for managing backup operations.
 */
@CrossOrigin(origins = "*") // Allows your React Frontend to talk to this Backend
@RestController
@RequestMapping("/api/backups")
public class BackupController {

    private final BackupService backupService;

    public BackupController(BackupService backupService) {
        this.backupService = backupService;
    }

    // ----------------------------------------------------------------
    // 1. TRIGGER BACKUP (POST)
    // ----------------------------------------------------------------
    @PostMapping("/trigger")
    public ResponseEntity<BackupJobResponse> triggerBackup(@RequestBody CreateBackupRequest request) {
        System.out.println("Received Trigger for DB: " + request.getDatabaseConfigId());
        BackupJob createdJob = backupService.triggerBackup(request);
        return new ResponseEntity<>(BackupJobResponse.fromEntity(createdJob), HttpStatus.CREATED);
    }

    // ----------------------------------------------------------------
    // 2. READ OPERATIONS (GET)
    // ----------------------------------------------------------------
    @GetMapping("/{id}")
    public ResponseEntity<BackupJobResponse> getBackupJobById(@PathVariable Long id) {
        BackupJob backupJob = backupService.getBackupJobById(id);
        return ResponseEntity.ok(BackupJobResponse.fromEntity(backupJob));
    }

    @GetMapping
    public ResponseEntity<List<BackupJobResponse>> getAllBackupJobs() {
        List<BackupJob> jobs = backupService.getAllBackupJobs();
        List<BackupJobResponse> responseList = jobs.stream()
                .map(BackupJobResponse::fromEntity)
                .collect(Collectors.toList());
        return ResponseEntity.ok(responseList);
    }

    @GetMapping("/latest")
    public ResponseEntity<List<BackupJobResponse>> getLatestBackups() {
        List<BackupJob> latestJobs = backupService.getLatestBackups();
        List<BackupJobResponse> responseList = latestJobs.stream()
                .map(BackupJobResponse::fromEntity)
                .collect(Collectors.toList());
        return ResponseEntity.ok(responseList);
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<List<BackupJobResponse>> getBackupJobsByStatus(@PathVariable BackupStatus status) {
        List<BackupJob> jobs = backupService.getBackupJobsByStatus(status);
        List<BackupJobResponse> responseList = jobs.stream()
                .map(BackupJobResponse::fromEntity)
                .collect(Collectors.toList());
        return ResponseEntity.ok(responseList);
    }

    // ----------------------------------------------------------------
    // 3. UPDATE & DELETE
    // ----------------------------------------------------------------
    @PatchMapping("/{id}/status")
    public ResponseEntity<BackupJobResponse> updateBackupStatus(
            @PathVariable Long id,
            @RequestParam BackupStatus status) {
        BackupJob updatedJob = backupService.updateBackupStatus(id, status);
        return ResponseEntity.ok(BackupJobResponse.fromEntity(updatedJob));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteBackupJob(@PathVariable Long id) {
        backupService.deleteBackupJob(id);
        return ResponseEntity.noContent().build();
    }

    // ----------------------------------------------------------------
    // 4. DOWNLOAD ENDPOINT (The "Mission Control" Feature)
    // ----------------------------------------------------------------
    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> downloadBackup(@PathVariable Long id) {
        // 1. Fetch the job metadata
        BackupJob job = backupService.getBackupJobById(id);

        // 2. Locate the physical file
        File file = new File(job.getFilePath());

        // 3. Check if file exists
        if (!file.exists()) {
            return ResponseEntity.notFound().build();
        }

        // 4. Prepare the resource
        Resource resource = new FileSystemResource(file);

        // 5. Force download via headers
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getName() + "\"")
                .body(resource);
    }
}