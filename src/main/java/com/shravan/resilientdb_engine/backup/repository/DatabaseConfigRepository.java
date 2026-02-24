package com.shravan.resilientdb_engine.backup.repository;

import com.shravan.resilientdb_engine.backup.entity.DatabaseConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DatabaseConfigRepository
        extends JpaRepository<DatabaseConfig, Long> {

}
