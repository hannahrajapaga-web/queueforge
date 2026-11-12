# QueueForge: Distributed Job Queue System with Image Processing Pipeline

A Redis-backed background job queue with retries, dead-lettering, and crash recovery, wrapped in a full-stack app where users upload images and watch them get processed.

- **Live app:** https://queue-forge.netlify.app/
- **Backend API:** https://queueforge-backend-2pzl.onrender.com
- **Frontend repo:** https://github.com/hannahrajapaga-web/queueforge-ui

## Tech stack

| Layer | Tech |
|---|---|
| Backend | Java, Spring Boot, Spring Security (JWT) |
| Queue | Redis (lists, sorted sets, Lua scripts) |
| Database | MySQL via Spring Data JPA |
| File storage | Local `uploads/` folder, or Supabase Storage when configured |
| Frontend | React (Vite), dark/light theme |
| Deployment | Backend on Render, frontend on Netlify |

## Features

- JWT login with two roles: **user** (sees only their own jobs) and **admin** (dashboard and dead-letter page)
- Multiple users can upload multiple images; each upload becomes a queued job
- Configurable pool of worker threads (`WORKER_COUNT`, default 3)
- Automatic retries with exponential backoff, then a dead-letter queue after 3 attempts
- Admin can retry jobs from the dead-letter queue
- Stats endpoint feeding the admin dashboard
- Image files live in file or cloud storage; Redis and MySQL only hold IDs and paths

## How the queue works

Redis structures:

| Key | Type | Purpose |
|---|---|---|
| `queue:pending` | List | Jobs waiting to be picked up |
| `queue:processing:<worker>` | List | Jobs currently held by a worker |
| `queue:delayed` | Sorted set | Failed jobs waiting for their retry time (score = due time) |
| `queue:dead` | List | Jobs that failed 3 times |

Flow:

1. The API saves the job in MySQL and pushes its ID onto `queue:pending`.
2. A worker takes the job with an atomic list move from `pending` to its own `processing` list, under a short-lived Redis lock (renewed automatically by a Lua script).
3. On success, the job is marked `COMPLETED` and removed from the processing list.
4. On failure, a **Lua script** atomically removes it from processing and either schedules a retry in `delayed` (backoff of 2 s, 4 s, ...) or pushes it to `dead` after 3 attempts.
5. A maintenance task runs every second and promotes due retries from `delayed` back to `pending` (also a Lua script).

### Delivery guarantee: at-least-once

A job stays in a worker's `processing` list until it finishes, so a crash can't silently drop it:

- **On startup**, any jobs left in processing lists are moved back to `pending`.
- **A sweeper** runs every 60 seconds and requeues any job stuck in `PROCESSING` for more than 5 minutes.

Trade-off: a job can occasionally run twice, which is the standard cost of at-least-once delivery.

## Load test results

Test: 100 mixed jobs (including jobs with simulated failures to exercise retries and the dead-letter queue). Script: [`loadtest/loadtest.mjs`](loadtest/loadtest.mjs).

| Workers | Total time | Throughput | Completed | Dead-lettered |
|---|---|---|---|---|
| 1 | 63.8 s | 1.57 jobs/s | 95 | 5 |
| 3 | 29.5 s | 3.39 jobs/s | 95 | 5 |
| 6 | 21.6 s | 4.64 jobs/s | 95 | 5 |

- Going from 1 to 6 workers cut total time by about 66% (63.8 s to 21.6 s).
- With 3 workers, 15 jobs completed only after retries, and latency was p50 14.9 s / p95 27.9 s.
- The 5 dead-lettered jobs are the ones designed to fail every attempt, so they reach the dead-letter queue as expected.

## Run locally

Prerequisites: Java, Docker, Node (only for the load test).

1. Start Redis and MySQL:

   ```
   docker compose up -d
   ```

2. MySQL is exposed on port **3308** in `docker-compose.yml`, so point the app at it:

   ```
   # PowerShell
   $env:DB_URL="jdbc:mysql://localhost:3308/queueforge?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
   ```

3. Run the backend:

   ```
   ./mvnw spring-boot:run        # Windows: mvnw.cmd spring-boot:run
   ```

4. Run the frontend from the [frontend repo](https://github.com/hannahrajapaga-web/queueforge-ui).

## Configuration

All settings are environment variables with local defaults in `application.properties`.

| Variable | Purpose |
|---|---|
| `PORT` | Server port (default 8080) |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | MySQL connection |
| `REDIS_URL` | Redis connection (use a `rediss://` URL for TLS) |
| `JWT_SECRET` | JWT signing key (**set your own in production**) |
| `WORKER_COUNT` | Number of worker threads (default 3) |
| `STORAGE_DIR` | Local upload folder (default `uploads`) |
| `SUPABASE_URL`, `SUPABASE_SECRET_KEY`, `SUPABASE_BUCKET` | Cloud image storage; leave `SUPABASE_URL` empty to use local storage |

## Project structure

```
src/main/java/com/queueforge/queueforge/
  job/       QueueService, JobWorker, QueueMaintenance and the job-related code
loadtest/    Load test script
```
