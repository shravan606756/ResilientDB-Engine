package com.shravan.resilientdb_engine.backup.service.impl;

import com.shravan.resilientdb_engine.backup.dto.CreateBackupRequest;
import com.shravan.resilientdb_engine.backup.entity.BackupJob;
import com.shravan.resilientdb_engine.backup.entity.DatabaseConfig;
import com.shravan.resilientdb_engine.backup.entity.DatabaseType;
import com.shravan.resilientdb_engine.backup.repository.BackupJobRepository;
import com.shravan.resilientdb_engine.backup.service.DatabaseConfigService;
import com.shravan.resilientdb_engine.backup.strategy.BackupStrategy;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackupServiceImplTest {

    @Test
    void triggerBackup_delegatesToAsyncWorker() {
        BackupJobRepository jobRepository = mock(BackupJobRepository.class);
        DatabaseConfigService configService = mock(DatabaseConfigService.class);
        BackupAsyncWorker backupAsyncWorker = mock(BackupAsyncWorker.class);
        BackupStrategy strategy = mock(BackupStrategy.class);

        DatabaseConfig config = new DatabaseConfig();
        config.setId(42L);
        config.setDatabaseType(DatabaseType.POSTGRESQL);
        when(configService.getById(42L)).thenReturn(config);

        when(jobRepository.saveAndFlush(any(BackupJob.class))).thenAnswer(invocation -> {
            BackupJob saved = invocation.getArgument(0);
            saved.setId(999L);
            return saved;
        });

        BackupServiceImpl service = new BackupServiceImpl(
                jobRepository,
                configService,
                Map.of(DatabaseType.POSTGRESQL.name(), strategy),
                backupAsyncWorker
        );

        BackupJob saved = service.triggerBackup(new CreateBackupRequest(42L, "nightly"));

        assertEquals(999L, saved.getId());
        verify(backupAsyncWorker, times(1)).performBackupAsync(999L, strategy);
    }

    @Test
    void triggerBackup_withoutStrategy_throws() {
        BackupJobRepository jobRepository = mock(BackupJobRepository.class);
        DatabaseConfigService configService = mock(DatabaseConfigService.class);
        BackupAsyncWorker backupAsyncWorker = mock(BackupAsyncWorker.class);

        DatabaseConfig config = new DatabaseConfig();
        config.setId(7L);
        config.setDatabaseType(DatabaseType.POSTGRESQL);
        when(configService.getById(7L)).thenReturn(config);
        when(jobRepository.saveAndFlush(any(BackupJob.class))).thenAnswer(invocation -> invocation.getArgument(0));

        BackupServiceImpl service = new BackupServiceImpl(
                jobRepository,
                configService,
                Map.of(),
                backupAsyncWorker
        );

        assertThrows(RuntimeException.class,
                () -> service.triggerBackup(new CreateBackupRequest(7L, "job")));
    }
}
