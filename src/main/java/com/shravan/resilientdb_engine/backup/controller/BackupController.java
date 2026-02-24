package com.shravan.resilientdb_engine.backup.controller;

import com.shravan.resilientdb_engine.backup.dto.BackupJobResponse;
import com.shravan.resilientdb_engine.backup.dto.CreateBackupRequest;
import com.shravan.resilientdb_engine.backup.entity.BackupJob;
import com.shravan.resilientdb_engine.backup.entity.BackupStatus;
import com.shravan.resilientdb_engine.backup.service.BackupService;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * REST Controller for managing backup operations.
 */
@CrossOrigin(origins = "http://localhost:5173")
@RestController
@RequestMapping("/api/backups")
public class BackupController {

    private final BackupService backupService;

    public BackupController(BackupService backupService) {
        this.backupService = backupService;
    }

    // ----------------------------------------------------------------
    // NEW: Trigger a Backup (The "Manager" Logic)
    // ----------------------------------------------------------------
    @PostMapping("/trigger")
    public ResponseEntity<BackupJobResponse> triggerBackup(@RequestBody CreateBackupRequest request) {
        System.out.println("Received Trigger for DB: " + request.getDatabaseConfigId());

        // This line will show RED ERROR until we fix the Service in Step 3
        BackupJob createdJob = backupService.triggerBackup(request);

        return new ResponseEntity<>(BackupJobResponse.fromEntity(createdJob), HttpStatus.CREATED);
    }

    // ----------------------------------------------------------------
    // EXISTING: Read Operations (Kept these exactly as they were)
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

    @PatchMapping("/{id}/status")
    public ResponseEntity<BackupJobResponse> updateBackupStatus(
            @PathVariable Long id,
            @RequestParam BackupStatus status) {
        BackupJob updatedJob = backupService.updateBackupStatus(id, status);
        return ResponseEntity.ok(BackupJobResponse.fromEntity(updatedJob));
    }

    // ----------------------------------------------------------------
    // EXISTING: Download Logic
    // ----------------------------------------------------------------
    @GetMapping("/download/{id}")
    public ResponseEntity<Resource> downloadBackup(@PathVariable Long id) {
        Resource resource = backupService.downloadBackup(id);
        String filename = resource.getFilename();
        if (filename == null) {
            filename = "download";
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteBackupJob(@PathVariable Long id) {
        backupService.deleteBackupJob(id);
        return ResponseEntity.noContent().build();
    }
}