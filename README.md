# ResilientDB-Engine: Multi-Database Backup Orchestrator

![Java](https://img.shields.io/badge/Java-21-orange?style=for-the-badge&logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.4-green?style=for-the-badge&logo=spring)
![Docker](https://img.shields.io/badge/Docker-Enabled-blue?style=for-the-badge&logo=docker)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-336791?style=for-the-badge&logo=postgresql)
![MySQL](https://img.shields.io/badge/MySQL-8.0-4479A1?style=for-the-badge&logo=mysql)
![MongoDB](https://img.shields.io/badge/MongoDB-7.0-47A248?style=for-the-badge&logo=mongodb)
![Build Status](https://img.shields.io/badge/Build-Stable-success?style=for-the-badge)

**Artifact:** Backend Orchestration Service  
**Architecture:** Multi-Strategy Asynchronous Engine  
**Runtime:** Java 21 / Spring Boot 3.4

## 1. Architectural Overview

The **ResilientDB-Engine** is a containerized, enterprise-grade backend system designed to automate, audit, and securely manage database backup lifecycles across heterogeneous environments.

Moving beyond simple shell scripts, this engine leverages native database utilities (`pg_dump`, `mysqldump`, `mongodump`) executed via a highly secure, non-blocking Java `ProcessBuilder` implementation. It is engineered to handle both internal Docker networks and external host machine databases flawlessly.

## 2. System Architecture & Design Patterns

The application structure strictly adheres to established software engineering principles, ensuring extensibility and robust performance:

### 2.1. Polymorphic Strategy Pattern
* **Implementations:** `PostgresBackupStrategy`, `MySQLBackupStrategy`, `MongoBackupStrategy`.
* **Design Principle:** Open/Closed Principle (OCP).
* **Implementation:** The system dynamically routes backup requests to the correct strategy based on the configured `DatabaseType`. This allows new database integrations (e.g., SQL Server, Oracle) to be injected without modifying the core orchestration logic.

### 2.2. Secure Credential Injection
* **Security Model:** Zero shell-injection vulnerabilities.
* **Implementation:** Passwords are never passed as raw shell arguments. The engine uses environment variable injection (`PGPASSWORD`, `MYSQL_PWD`) and secure connection string URIs (MongoDB) to authenticate, preventing process hanging and unauthorized credential logging.

### 2.3. Asynchronous Worker Pattern
* **Design Principle:** Resource Optimization & Non-Blocking I/O.
* **Implementation:** Long-running I/O operations (extracting and writing `.sql` or `.archive` files) are offloaded to managed worker threads via Spring's `@Async`. The REST API returns immediate standard HTTP responses (`201 Created`, `202 Accepted`), allowing clients to poll job statuses without thread starvation.

## 3. Infrastructure & Topology

The system is deployed as a multi-container topology orchestrating services via `docker-compose.yml`:

1. **Application Container (`resilientdb_app`):**
    * **Port:** `8080` (API Gateway)
    * **Tooling:** Pre-loaded with custom Ubuntu utility layers containing `postgresql-client-16`, `mysql-client`, and `mongodb-database-tools`.
    * **Persistence:** Mounts a host bind volume (`./backups:/app/backups`) to ensure generated backup artifacts are safely stored on the host OS.
2. **Persistence Layer (`resilientdb_postgres`):**
    * **Port:** `5432` (Internal) / `5433` (Exposed)
    * **Role:** Stores relational data including Job Metadata, Configurations, and run history.
    * **Storage:** Utilizes a Docker Named Volume (`pgdata`) for optimized binary storage.

## 4. API Reference

The engine exposes a clean, RESTful interface for external UI dashboards or CLI tools to interact with:

| Method | Endpoint | Description | Payload / Response |
|--------|----------|-------------|--------------------|
| **POST** | `/api/databases` | Register a new target database configuration. | JSON (dbName, host, port, type, credentials) |
| **POST** | `/api/backups/trigger` | Manually trigger an async backup job. | JSON (databaseConfigId, jobName) |
| **GET** | `/api/backups/{id}` | Poll the execution status (`PENDING`, `COMPLETED`, `FAILED`). | Returns metadata including `sizeBytes`. |
| **DELETE** | `/api/backups/{id}` | Securely remove job metadata and associated local files. | `204 No Content` |

## 5. Deployment & Execution

### Prerequisites
* Docker Engine 24.0+
* Maven (or use the included wrapper)

### Quick Start
The system is designed for one-click deployment.

1. **Clone and Build:**
   ```bash
   git clone [https://github.com/shravan606756/resilient-db-engine.git](https://github.com/shravan606756/resilient-db-engine.git)
   cd resilientdb-engine
   ./mvnw clean package -DskipTests
   ```

2. **Start the Stack:**
   This command builds the multi-tool Docker image and starts the containers in detached mode.
   ```bash
   docker compose up -d --build
   ```

3. **Verify Tooling Installation (Optional):**
   ```bash
   docker exec -it resilientdb_app sh -c "pg_dump --version && mysqldump --version && mongodump --version"
   ```

### Connecting to External (Host) Databases
To back up a database running on your local host machine (outside of Docker), configure your target payload to use Docker's internal host gateway:
* **Host:** `host.docker.internal`

*Note: Ensure your local database allows external connections (e.g., updating `pg_hba.conf` and Windows firewall rules for port 5432).*

## 6. Future Roadmap
* **React/Vite Dashboard:** A frontend UI to visualize backup histories, configurations, and trigger jobs visually.
* **Cloud Storage Adapters:** Seamless integration with AWS S3 and Google Cloud Storage to replicate local backup artifacts to immutable cloud buckets.
* **Cron Scheduling:** Automated daily/weekly backup scheduling integration.