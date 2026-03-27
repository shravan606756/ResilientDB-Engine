# ResilientDB Engine
### Multi-Database Backup Orchestrator

*Technical Architecture & Reference Documentation*

| | |
|---|---|
| **Runtime** | Java 21 / Spring Boot 3.4 |
| **Build** | Maven / Docker Compose |
| **DB Client** | PostgreSQL 16 |
| **Targets** | PostgreSQL, MySQL 8, MongoDB 7 |
| **Pattern** | Strategy + Async Worker |
| **Exposed Port** | 8080 (REST API) |

---

## Table of Contents

1. [The Problem This Project Solves](#1-the-problem-this-project-solves)
2. [High-Level System Architecture](#2-high-level-system-architecture)
3. [Component Architecture](#3-component-architecture)
4. [The Polymorphic Strategy Pattern](#4-the-polymorphic-strategy-pattern)
5. [Asynchronous Execution Architecture](#5-asynchronous-execution-architecture)
6. [Data Model](#6-data-model)
7. [Docker Infrastructure](#7-docker-infrastructure)
8. [API Reference](#8-api-reference)
9. [Deployment](#9-deployment)
10. [Key Design Decisions and Trade-offs](#10-key-design-decisions-and-trade-offs)
11. [Future Roadmap](#11-future-roadmap)

---

## 1. The Problem This Project Solves

Most backup setups are a cron job running `pg_dump` and hoping for the best. No retries, no timeouts, no audit trail — if it silently fails, you find out during a restore.

ResilientDB Engine treats backup jobs as first-class entities: every job is tracked, retried on failure, killed if it hangs, and queryable by status at any time.

### 1.1 Specific Problems Addressed

| Problem | How the Engine Solves It |
|---|---|
| Silent hung processes | Every backup has a 30-second OS-level hard kill timeout. The actual `pg_dump`/`mysqldump` process is terminated via `ProcessHandle`, not just the Java thread wrapper. |
| No audit trail | Every job is persisted to PostgreSQL with full lifecycle timestamps (`createdAt`, `startedAt`, `completedAt`), status transitions, retry counts, and error messages. |
| Transient failures | The retry engine re-attempts failed backups up to 3 times with a 5-second backoff before permanently marking a job `FAILED`. |
| HTTP thread starvation | Backup execution is fully asynchronous. The API returns `201` immediately. A dedicated Spring thread pool handles all I/O work off the request thread. |
| Credential exposure | Passwords are never passed as shell arguments. `PGPASSWORD` and `MYSQL_PWD` are injected as process environment variables. MongoDB uses a URI with `authSource`. |
| Multi-database complexity | The Strategy Pattern routes each backup to the correct native tool automatically based on the registered `DatabaseType`, with no conditional logic in the core service. |
| Disk accumulation | A cleanup routine runs after every completed job, retaining the 5 most recent backup files and deleting older ones automatically. |

---

## 2. High-Level System Architecture

The system is composed of three logical tiers: a Spring Boot REST layer that accepts requests and immediately returns, an asynchronous worker layer that executes the backup using native OS tools, and a PostgreSQL persistence layer that records every state transition.

**Figure 1: End-to-end request and job lifecycle flow**

```
React / Vite                     Spring Boot Application (Port 8080)
Dashboard UI
    |                                        |
    |---- HTTP POST /api/backups/trigger ---->|
    |                                        |
    |                              BackupController
    |                                        |
    |                              BackupServiceImpl
    |                                        |
    |                              saveAndFlush(PENDING) --> PostgreSQL [commit]
    |                                        |
    |<---- 201 CREATED (job: PENDING) --------|
    |                                        |
    |                              BackupAsyncWorker (@Async)
    |                              [task enqueued in thread pool]
    |                                        |
    |                              fetchJob --> set IN_PROGRESS
    |                                        |
    |                              executeWithTimeout(30s)
    |                                        |
    |                              ProcessBuilder (OS)
    |                              pg_dump / mysqldump / mongodump
    |                                        |
    |                              set COMPLETED / FAILED
    |                              saveAndFlush()
    |                                        |
    |---- HTTP GET /api/backups (3s polling)->|
    |<---- status updates --------------------|
    |                                        |
    |                               PostgreSQL DB
    |                               backup_operations
    |                               database_configs
```

The critical design decision is the separation between the HTTP thread and the backup execution thread. The REST endpoint returns a `201 CREATED` with the job in `PENDING` state before a single byte of backup data has been written. The UI polls `GET /api/backups` every 3 seconds to observe status transitions.

---

## 3. Component Architecture

### 3.1 REST Layer

Three controllers handle all external interaction:

- **`BackupController` (`/api/backups`):** Handles backup job CRUD. Trigger, read by ID, list all, filter by status, update status, delete, and download. The download endpoint streams the physical backup file using `FileSystemResource` with `Content-Disposition: attachment`.

- **`DatabaseConfigController` (`/api/databases`):** Registers and lists database connection configurations. Configurations are stored as `DatabaseConfig` entities and referenced by ID when triggering backups, so credentials are never sent in the trigger request.

- **`HealthCheckController` (`/api/health`):** Exposes thread pool diagnostics at `/api/health/executor` including active thread count, queue depth, queue capacity, and a computed health status (`healthy` / `degraded` / `critical`). Useful for monitoring executor saturation.

### 3.2 Service Layer

#### 3.2.1 `BackupServiceImpl`

Owns the trigger flow. Has no `@Transactional` annotation on `triggerBackup()` deliberately: the `saveAndFlush()` call commits the `PENDING` row to PostgreSQL atomically before the async dispatch is made. This eliminates the read-before-commit race condition where the async worker calls `findById` before the row is visible.

Strategy resolution uses Spring's automatic `Map` injection. Because each strategy is annotated `@Component("POSTGRESQL")`, `@Component("MYSQL")`, and `@Component("MONGODB")`, Spring populates `Map<String, BackupStrategy>` with the bean name as key. The lookup is a single `map.get(databaseType.name())` with no `if/else` or `switch`.

#### 3.2.2 `BackupAsyncWorker`

A dedicated `@Service` class holding the `@Async` method. This is architecturally significant: Spring's `@Async` proxy only works when the method is called on a Spring-managed proxy, not through `this.method()` on the same bean. Extracting the async worker into its own class guarantees the Spring proxy intercepts the call and dispatches to the `backupTaskExecutor` thread pool every time.

The worker is responsible for the full execution lifecycle: fetching the job, transitioning to `IN_PROGRESS`, running the strategy inside a timeout boundary, handling retries, and writing the final terminal state. Each `saveAndFlush()` inside the worker is an independent, immediately-committed transaction.

#### 3.2.3 `DatabaseConfigService`

A thin service over `DatabaseConfigRepository`. Exposes `registerDatabase()` and `getById()`. The `getById()` method throws a `RuntimeException` if the ID is not found, which propagates as a `500` to the caller and prevents a backup job from being created against a non-existent configuration.

---

## 4. The Polymorphic Strategy Pattern

The Strategy Pattern is the architectural backbone of the backup execution layer. It implements the Open/Closed Principle: the core orchestration logic (`BackupServiceImpl`, `BackupAsyncWorker`) is closed for modification, but open for extension. Adding support for a new database type requires only creating a new class that implements `BackupStrategy` and annotating it with the correct `@Component` name.

**Figure 2: Strategy routing based on `DatabaseType`**

```
BackupServiceImpl.triggerBackup()
        |
        | Map<String, BackupStrategy> backupStrategies
        | (Spring auto-wires by @Component bean name)
        |
        +-- config.getDatabaseType() == POSTGRESQL
        |       --> backupStrategies.get("POSTGRESQL")
        |       --> PostgresBackupStrategy.execute()
        |           ProcessBuilder: pg_dump ...
        |           env: PGPASSWORD=<secret>
        |
        +-- config.getDatabaseType() == MYSQL
        |       --> backupStrategies.get("MYSQL")
        |       --> MySQLBackupStrategy.execute()
        |           ProcessBuilder: mysqldump ...
        |           env: MYSQL_PWD=<secret>
        |
        +-- config.getDatabaseType() == MONGODB
                --> backupStrategies.get("MONGODB")
                --> MongoBackupStrategy.execute()
                    ProcessBuilder: mongodump ...
                    uri: mongodb://user:pass@host/db
```

### 4.1 The `BackupStrategy` Interface

The interface defines two members:

- **`getSupportedType()`:** Returns the `DatabaseType` enum value this strategy handles. Used for documentation and self-description.

- **`execute(BackupJob job, ProcessHolder processHolder)`:** Executes the backup. The `ProcessHolder` parameter is a thread-safe holder that the strategy populates with the live OS `Process` object (`pg_dump`, `mysqldump`, or `mongodump`) immediately after `ProcessBuilder.start()`. The timeout monitor retrieves this handle to call `destroyForcibly()` if the 30-second deadline is exceeded.

The `ProcessHolder` is a `volatile` field wrapper:

```java
class ProcessHolder {
    private volatile Process process;

    public void setProcess(Process p) { this.process = p; }
    public Process getProcess() { return this.process; }
}
```

The `volatile` keyword ensures the process reference written by the `BackupExec` thread is immediately visible to the `TimeoutMonitor` thread without requiring explicit synchronization.

### 4.2 Credential Security Model

| Database | Credential Mechanism | Why |
|---|---|---|
| PostgreSQL | `PGPASSWORD` env var | `pg_dump` with `--no-password` refuses interactive prompts. `PGPASSWORD` is read by the client library, not logged by the shell. |
| MySQL | `MYSQL_PWD` env var | `mysqldump` with `--no-defaults` prevents `my.cnf` conflicts. `MYSQL_PWD` is deprecated in newer versions but remains the only non-interactive option for `ProcessBuilder`. |
| MongoDB | URI connection string | `mongodump --uri` embeds credentials in the URI passed to the driver, not as separate shell arguments, preventing shell history and process list exposure. |

---

## 5. Asynchronous Execution Architecture

The asynchronous layer is the most technically significant part of the system. It must satisfy three competing requirements: the HTTP thread must return immediately, backup jobs must execute with bounded runtime, and failed jobs must retry without losing state.

**Figure 3: Thread interaction diagram for a single backup job**

```
HTTP Thread (Tomcat)                    BackupWorker-N (Executor Pool)
─────────────────────────────────────   ────────────────────────────────────

triggerBackup() called
        |
        | saveAndFlush(job, PENDING) --> PostgreSQL commit [immediate]
        |
        | backupAsyncWorker.performBackupAsync(id, strat)
        |                                       |
        |                               [task enqueued in LinkedBlockingQueue]
        |                                       |
[returns 201 to client]                 thread picks up task
                                                |
                                        fetchJobWithRetry(id) --> findById()
                                        job.status = IN_PROGRESS
                                        saveAndFlush() --> PostgreSQL commit
                                                |
                                        executeBackupWithTimeout(30s)
                                                |
                                        new Thread: BackupExec-Job#N
                                        strategy.execute(job, holder)
                                        ProcessBuilder.start()
                                        holder.setProcess(osProcess)
                                        [pg_dump / mysqldump running]
                                        job.setFilePath / setSizeBytes
                                                |
                                        [if > 30s]
                                        TimeoutMonitor fires
                                        holder.getProcess().destroyForcibly()
                                        backupFuture.completeExceptionally()
                                        job.status = FAILED
                                        saveAndFlush()
                                                |
                                        [success path]
                                        job.status = COMPLETED
                                        saveAndFlush()
                                        cleanupOldBackups()
```

### 5.1 Thread Pool Configuration

The executor is configured in `AsyncConfig`:

```
backupTaskExecutor
├── CorePoolSize   : 5   (always-on workers)
├── MaxPoolSize    : 10  (burst capacity)
├── QueueCapacity  : 25  (LinkedBlockingQueue)
├── RejectionPolicy: CallerRunsPolicy
├── KeepAlive      : 60s (core threads time out when idle)
├── Shutdown       : awaits termination 60s
├── Thread naming  : BackupWorker-{N}
└── Monitored via  : GET /api/health/executor

BackupTimeoutMonitor (dedicated ScheduledExecutorService)
├── Pool size      : 2 daemon threads
├── Role           : schedules a 30s kill task per backup job
└── On fire        : osProcess.destroyForcibly()
                     backupFuture.completeExceptionally()
```

`CallerRunsPolicy` is the rejection handler. If the queue is full (25 jobs waiting with 10 threads active), the next submission runs on the HTTP request thread instead of being dropped. This provides backpressure without silent data loss.

### 5.2 The Timeout Mechanism

The timeout mechanism kills the actual OS process, not just the Java thread. This distinction matters: interrupting a Java thread that is blocked on `Process.waitFor()` does not kill the `pg_dump` process. The child process continues running, holding a database connection and writing to disk, while the Java thread silently exits.

The correct sequence is:

1. `strategy.execute()` spawns the OS process and stores it in `ProcessHolder`.
2. A `ScheduledFuture` is submitted to the `TimeoutMonitor` executor with a 30-second delay.
3. If the `CompletableFuture` is not done after 30 seconds, the monitor calls `holder.getProcess().destroyForcibly()`.
4. The monitor waits up to 2 seconds for the OS process to terminate.
5. The monitor then interrupts the `BackupExec` thread and completes the future exceptionally with `TimeoutException`.
6. The worker catches `TimeoutException`, sets status `FAILED`, saves, and returns.

### 5.3 Job State Machine

**Figure 4: Backup job state transitions**

```
[PENDING]
    |
    | BackupAsyncWorker picks up
    v
[IN_PROGRESS] <──────────────────────────────+
    |                                        |
    | strategy.execute() throws              |
    v                                        |
retryCount++                                 |
    |                                        |
    +-- retryCount < maxRetries(3) -- sleep 5s (retry loop) -->+
    |
    +-- retryCount >= maxRetries
    |           |
    |           v
    |       [FAILED] (permanent)
    |
    +-- TimeoutException (30s hard kill)
    |           |
    |           v
    |       [FAILED] (timeout)
    |
    +-- success
            |
            v
        [COMPLETED]
```

The state machine has exactly one terminal success state (`COMPLETED`) and two terminal failure states (`FAILED` via retry exhaustion, `FAILED` via timeout). The `CANCELLED` state is reserved for future manual cancellation support via the `PATCH /api/backups/{id}/status` endpoint.

---

## 6. Data Model

### 6.1 `BackupJob` Entity (`backup_operations` table)

| Column | Type | Description |
|---|---|---|
| `id` | `BIGINT PK` | Auto-generated identity. |
| `jobName` | `VARCHAR` | Human-readable label provided at trigger time. |
| `status` | `VARCHAR (enum)` | `PENDING`, `IN_PROGRESS`, `COMPLETED`, `FAILED`, `CANCELLED`. |
| `databaseType` | `VARCHAR (enum)` | `POSTGRESQL`, `MYSQL`, `MONGODB` (and reserved others). |
| `database_config_id` | `BIGINT FK` | Foreign key to `database_configs`. `EAGER` fetched. |
| `filePath` | `TEXT` | Absolute path to the generated backup file on the container filesystem. |
| `sizeBytes` | `BIGINT` | File size in bytes, set after successful backup. |
| `errorMessage` | `TEXT` | Full error detail including retry context. `TEXT` type to handle long stack traces. |
| `retryCount` | `INT` | Number of attempts made. Starts at 0. |
| `maxRetries` | `INT` | Configured maximum retries. Defaults to 3. |
| `createdAt` | `TIMESTAMP` | Set by `@PrePersist`. Immutable after insert (`updatable=false`). |
| `startedAt` | `TIMESTAMP` | Set when async worker transitions to `IN_PROGRESS`. |
| `completedAt` | `TIMESTAMP` | Set on `COMPLETED` terminal state. |
| `updatedAt` | `TIMESTAMP` | Set by `@PreUpdate` on every save. |

### 6.2 `DatabaseConfig` Entity (`database_configs` table)

| Column | Type | Description |
|---|---|---|
| `id` | `BIGINT PK` | Auto-generated identity. |
| `databaseType` | `VARCHAR (enum)` | Determines which `BackupStrategy` is used. |
| `host` | `VARCHAR` | Hostname or IP. Use `host.docker.internal` for local machine databases. |
| `port` | `INTEGER` | Database port. |
| `dbName` | `VARCHAR` | Target database name. |
| `username` | `VARCHAR` | Authentication username. |
| `password` | `VARCHAR` | Authentication password. Stored in plaintext (intended for local/demo use). |
| `createdAt` | `TIMESTAMP` | Set by `@PrePersist`. |

---

## 7. Docker Infrastructure

**Figure 5: Container topology**

```
Host Machine
┌──────────────────────────────────────────────────────────┐
│ Docker Compose Network: resilientdb_network               │
│                                                           │
│  ┌───────────────────────────────────────────────────┐   │
│  │ resilientdb_app                                   │   │
│  │ Image: custom Ubuntu layer    Port: 8080 (exposed)│<──┼──> Browser / Postman
│  │ Tools installed:                                  │   │
│  │   postgresql-client-16                            │   │
│  │   mysql-client                                    │   │
│  │   mongodb-database-tools                          │   │
│  │                                                   │   │
│  │ Volume: ./backups:/app/backups (bind mount)       │   │
│  └──────────────────────┬────────────────────────────┘   │
│                         │ JDBC / internal network         │
│                         v                                 │
│  ┌───────────────────────────────────────────────────┐   │
│  │ resilientdb_postgres                              │   │
│  │ Image: postgres:16            Port: 5432 (internal│   │
│  │ Database: resilientdb_meta    Port: 5433 (exposed)│   │
│  │ Tables:                                           │   │
│  │   backup_operations                               │   │
│  │   database_configs                                │   │
│  │                                                   │   │
│  │ Volume: pgdata (named)                            │   │
│  └───────────────────────────────────────────────────┘   │
│                                                           │
│  External DBs (host.docker.internal):                     │
│    PostgreSQL :5432   MySQL :3306   MongoDB :27017        │
└──────────────────────────────────────────────────────────┘
```

### 7.1 Application Container

The application container is built on a custom Ubuntu base that installs the exact CLI tool versions required:

- **`postgresql-client-16`:** Provides `pg_dump`. Version-pinned to 16 to match the PostgreSQL wire protocol version in common use.
- **`mysql-client`:** Provides `mysqldump`. The `--column-statistics=0` flag is passed explicitly to suppress the `INFORMATION_SCHEMA.COLUMN_STATISTICS` query that fails on older MySQL server versions.
- **`mongodb-database-tools`:** Provides `mongodump`. Installed separately from the MongoDB server package to keep the image lean.

The `backups` directory is bind-mounted from the host (`./backups:/app/backups`). This means generated backup files survive container restarts and are directly accessible on the host filesystem without `docker cp`.

### 7.2 Persistence Container

The PostgreSQL 16 container stores job metadata and configurations only. It does not store backup data. A named Docker volume (`pgdata`) is used for the PostgreSQL data directory rather than a bind mount, which provides better I/O performance for database workloads.

The internal port `5432` is used for JDBC connections from the application container. Port `5433` is exposed to the host for direct inspection with `psql` or a GUI client during development.

---

## 8. API Reference

### 8.1 Database Configuration Endpoints

| Method | Endpoint | Request Body | Response |
|---|---|---|---|
| `POST` | `/api/databases` | `DatabaseConfig` JSON | `201` + saved config with `id` |
| `GET` | `/api/databases` | — | `200` + list of all configs |

### 8.2 Backup Job Endpoints

| Method | Endpoint | Request | Response |
|---|---|---|---|
| `POST` | `/api/backups/trigger` | `{ databaseConfigId, jobName }` | `201` + `BackupJobResponse` (PENDING) |
| `GET` | `/api/backups` | — | `200` + list of all jobs |
| `GET` | `/api/backups/{id}` | — | `200` + single job |
| `GET` | `/api/backups/latest` | — | `200` + top 10 by `createdAt` |
| `GET` | `/api/backups/status/{status}` | — | `200` + jobs filtered by status |
| `PATCH` | `/api/backups/{id}/status` | `?status=CANCELLED` | `200` + updated job |
| `DELETE` | `/api/backups/{id}` | — | `204` No Content, deletes file |
| `GET` | `/api/backups/{id}/download` | — | `200` + octet-stream (`Content-Disposition: attachment`) |

### 8.3 Health Endpoints

| Method | Endpoint | Response |
|---|---|---|
| `GET` | `/api/health/ping` | `{ status: ok, service, timestamp }` |
| `GET` | `/api/health/executor` | Thread pool stats: `active_threads`, `pool_size`, `queue_size`, `status` (healthy / degraded / critical) |

---

## 9. Deployment

### 9.1 Prerequisites

- **Docker Engine 24.0+:** Required for compose v2 syntax and bind mount support.
- **Maven 3.9+ or included wrapper:** For building the JAR before Docker image creation.

### 9.2 Build and Start

```bash
# 1. Clone the repository
git clone https://github.com/shravan606756/resilient-db-engine.git
cd resilientdb-engine

# 2. Build the application JAR
./mvnw clean package -DskipTests

# 3. Build the Docker image and start all containers
docker compose up -d --build

# 4. Verify all tools are installed inside the container
docker exec -it resilientdb_app sh -c \
  "pg_dump --version && mysqldump --version && mongodump --version"

# 5. Confirm the API is reachable
curl http://localhost:8080/api/health/ping
```

### 9.3 Connecting to Databases on the Host Machine

> **Note:** When running inside Docker, `localhost` inside the container refers to the container itself, not the host machine.
>
> To target a database running on your local machine, use `host.docker.internal` as the `host` value when registering a database configuration.
>
> Additionally, ensure the target database is configured to accept connections from external addresses (e.g., `listen_addresses = '*'` in `postgresql.conf`, and an appropriate entry in `pg_hba.conf`).

Example registration payload for a host machine PostgreSQL database:

```json
{
  "databaseType": "POSTGRESQL",
  "host": "host.docker.internal",
  "port": 5432,
  "dbName": "myapp_production",
  "username": "backup_user",
  "password": "<password>"
}
```

---

## 10. Key Design Decisions and Trade-offs

| Decision | Rationale | Trade-off |
|---|---|---|
| Native CLI tools over JDBC | `pg_dump` and `mysqldump` produce complete, consistent snapshots including schema, stored procedures, and triggers that are difficult to replicate via JDBC. | Requires tool installation in the container. Versions must be compatible with the target server. |
| `ProcessBuilder` over `Runtime.exec()` | `ProcessBuilder` allows per-process environment variable injection without shell interpretation, preventing injection attacks and credential leakage. | More verbose. Arguments must be passed as discrete tokens, not a shell string. |
| Dedicated `BackupAsyncWorker` class | Spring `@Async` only works through a proxy. Self-invocation via `this.` bypasses the proxy. A separate bean guarantees proxy-mediated dispatch on every call. | Additional class. Slightly less obvious code locality. |
| No `@Transactional` on `triggerBackup()` | `saveAndFlush()` without an enclosing transaction commits immediately. The async worker can always `findById()` successfully on the first attempt. | No rollback if strategy lookup fails after save. The dangling `PENDING` row must be cleaned up manually. |
| OS-level process kill on timeout | Java thread interruption does not kill child processes. `destroyForcibly()` on the OS process handle is the only reliable mechanism. | Partial backup files may be left on disk after a kill. The cleanup routine only deletes files older than the 5 most recent. |
| 3s polling over WebSocket | Simpler infrastructure. No session state. Works correctly with CORS. Sufficient for demo purposes. | Slight status display lag. Not suitable for high-frequency update scenarios. |

---

## 11. Deployment

### Backend — AWS EC2 via CD Pipeline

The backend is deployed to **AWS EC2** through a GitHub Actions CD pipeline. On every push to `main`, the pipeline builds the JAR, packages it into a Docker image, pushes it to **Amazon ECR**, and SSH-deploys it to the EC2 instance.

**Pipeline overview (`.github/workflows/deploy.yml`):**

```yaml
name: Deploy to AWS EC2

on:
  push:
    branches: [main]

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3

      - name: Set up JDK 21
        uses: actions/setup-java@v3
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Build JAR
        run: ./mvnw clean package -DskipTests

      - name: Configure AWS credentials
        uses: aws-actions/configure-aws-credentials@v2
        with:
          aws-access-key-id: ${{ secrets.AWS_ACCESS_KEY_ID }}
          aws-secret-access-key: ${{ secrets.AWS_SECRET_ACCESS_KEY }}
          aws-region: us-east-1

      - name: Login to Amazon ECR
        uses: aws-actions/amazon-ecr-login@v1

      - name: Build and push Docker image
        run: |
          docker build -t ${{ secrets.ECR_REGISTRY }}/resilientdb-engine:latest .
          docker push ${{ secrets.ECR_REGISTRY }}/resilientdb-engine:latest

      - name: Deploy to EC2 via SSH
        uses: appleboy/ssh-action@v1
        with:
          host: ${{ secrets.EC2_HOST }}
          username: ec2-user
          key: ${{ secrets.EC2_SSH_KEY }}
          script: |
            aws ecr get-login-password --region us-east-1 | \
              docker login --username AWS --password-stdin ${{ secrets.ECR_REGISTRY }}
            docker pull ${{ secrets.ECR_REGISTRY }}/resilientdb-engine:latest
            docker stop resilientdb-engine || true
            docker rm resilientdb-engine || true
            docker run -d --name resilientdb-engine \
              -p 8080:8080 \
              -e SPRING_DATASOURCE_URL=${{ secrets.DB_URL }} \
              -e SPRING_DATASOURCE_USERNAME=${{ secrets.DB_USER }} \
              -e SPRING_DATASOURCE_PASSWORD=${{ secrets.DB_PASS }} \
              ${{ secrets.ECR_REGISTRY }}/resilientdb-engine:latest
```

**Required GitHub secrets:** `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `ECR_REGISTRY`, `EC2_HOST`, `EC2_SSH_KEY`, `DB_URL`, `DB_USER`, `DB_PASS`.

The EC2 instance should have Docker installed, the ECR registry accessible via IAM role, and port `8080` open in its security group. Use **Amazon RDS (PostgreSQL)** as the managed database to replace the local `resilientdb_postgres` container.

> **Alternative — GCP Compute Engine:** If deploying to GCP instead, replace ECR with **Google Artifact Registry** and the EC2 SSH step with a `gcloud compute ssh` command or Cloud Run deployment. The GitHub Actions structure remains identical — only the registry login and deploy target change.

---

### Frontend — Vercel Deployment

The React/Vite dashboard deploys to **Vercel**. Point the API base URL at the EC2 instance's public IP or domain.

```bash
# Install Vercel CLI and deploy
npm i -g vercel
vercel deploy
```

In your Vercel project settings, add:

```
VITE_API_BASE_URL=http://<your-ec2-public-ip>:8080
```

Then reference it in your API client:

```js
const BASE_URL = import.meta.env.VITE_API_BASE_URL;
```

Vercel handles CI/CD automatically on every push to `main` — no further configuration needed.

---

*ResilientDB Engine | Java 21 / Spring Boot 3.4 | [github.com/shravan606756/resilient-db-engine](https://github.com/shravan606756/resilient-db-engine)*