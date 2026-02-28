package com.shravan.resilientdb_engine.backup.strategy;

import com.shravan.resilientdb_engine.backup.entity.BackupJob;
import com.shravan.resilientdb_engine.backup.entity.DatabaseType;

/**
 * UPDATED: BackupStrategy interface with ProcessHolder support for proper OS-level cleanup
 */
public interface BackupStrategy {

    DatabaseType getSupportedType();

    /**
     * Execute the backup strategy.
     *
     * @param job The backup job to execute
     * @param processHolder Holder to store the OS process (pg_dump, mysqldump, etc.)
     *                      so it can be killed on timeout
     */
    void execute(BackupJob job, ProcessHolder processHolder);

    /**
     * Simple holder class for passing Process references between threads.
     * This allows the timeout monitor to kill the actual OS process.
     */
    class ProcessHolder {
        private volatile Process process;

        public void setProcess(Process process) {
            this.process = process;
        }

        public Process getProcess() {
            return process;
        }
    }
}