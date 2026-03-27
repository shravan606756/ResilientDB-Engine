package com.shravan.resilientdb_engine.backup.service.impl;

import com.shravan.resilientdb_engine.backup.entity.BackupJob;
import com.shravan.resilientdb_engine.backup.entity.BackupStatus;
import com.shravan.resilientdb_engine.backup.entity.DatabaseType;
import com.shravan.resilientdb_engine.backup.repository.BackupJobRepository;
import com.shravan.resilientdb_engine.backup.strategy.BackupStrategy;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackupAsyncWorkerTest {

    private BackupJob sampleJob() {
        BackupJob job = new BackupJob();
        job.setId(100L);
        job.setJobName("job-100");
        job.setDatabaseType(DatabaseType.POSTGRESQL);
        job.setStatus(BackupStatus.PENDING);
        job.setRetryCount(0);
        job.setMaxRetries(1);
        return job;
    }

    @Test
    void performBackupAsync_success_reachesCompleted() {
        BackupJobRepository repository = mock(BackupJobRepository.class);
        BackupAsyncWorker worker = new BackupAsyncWorker(repository);
        BackupJob job = sampleJob();

        when(repository.findById(100L)).thenReturn(Optional.of(job));
        when(repository.saveAndFlush(any(BackupJob.class))).thenAnswer(invocation -> invocation.getArgument(0));

        BackupStrategy strategy = mock(BackupStrategy.class);
        worker.performBackupAsync(100L, strategy);

        ArgumentCaptor<BackupJob> captor = ArgumentCaptor.forClass(BackupJob.class);
        verify(repository, atLeast(2)).saveAndFlush(captor.capture());
        BackupJob lastSaved = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertEquals(BackupStatus.COMPLETED, lastSaved.getStatus());
    }

    @Test
    void performBackupAsync_failure_reachesFailed_notPending() {
        BackupJobRepository repository = mock(BackupJobRepository.class);
        BackupAsyncWorker worker = new BackupAsyncWorker(repository);
        BackupJob job = sampleJob();

        when(repository.findById(100L)).thenReturn(Optional.of(job));
        when(repository.saveAndFlush(any(BackupJob.class))).thenAnswer(invocation -> invocation.getArgument(0));

        BackupStrategy strategy = mock(BackupStrategy.class);
        doThrow(new RuntimeException("boom")).when(strategy)
                .execute(any(BackupJob.class), any(BackupStrategy.ProcessHolder.class));

        worker.performBackupAsync(100L, strategy);

        ArgumentCaptor<BackupJob> captor = ArgumentCaptor.forClass(BackupJob.class);
        verify(repository, atLeast(2)).saveAndFlush(captor.capture());
        BackupJob lastSaved = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertEquals(BackupStatus.FAILED, lastSaved.getStatus());
    }
}
