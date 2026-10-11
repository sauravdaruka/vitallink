# Local runbook

How to start, check, stop and fix the service on a laptop. This grows as more parts are added (message broker, cache, load test).

## What you need
- Docker (running)
- Java 25
- Nothing else: Maven comes with the project (`./mvnw`).

## Start

1. Start the database:
   ```
   docker compose up -d
   ```
   Wait until it is healthy:
   ```
   docker compose ps
   ```
   The `postgres` service should show `(healthy)`.

2. Start the service:
   ```
   ./mvnw spring-boot:run
   ```
   Database migrations (Flyway) run automatically at startup.

## Check it's working

| Check | Command | Expected |
|---|---|---|
| Service is up | `curl localhost:8080/actuator/health` | `{"status":"UP"}` |
| Connection pool size | `curl localhost:8080/actuator/metrics/hikaricp.connections.max` | `10` |
| Threads waiting for a DB connection | `curl localhost:8080/actuator/metrics/hikaricp.connections.pending` | `0` when idle |
| Database is reachable | `docker compose exec postgres psql -U vitallink -d vitallink -c 'select 1'` | `1` |
| Migrations that have run | `docker compose exec postgres psql -U vitallink -d vitallink -c 'select version, description, success from flyway_schema_history'` | one row per migration |

## Build and test

| Task | Command |
|---|---|
| Full build: compile, tests, formatting check | `./mvnw verify` |
| Tests only | `./mvnw test` |
| Fix formatting | `./mvnw spotless:apply` |

Tests start their own PostgreSQL in Docker (Testcontainers), so Docker must be running. They don't use the `docker compose` database.

## Stop and reset

| Task | Command | Data |
|---|---|---|
| Stop the service | `Ctrl+C` in its terminal | – |
| Stop the database | `docker compose stop` | kept |
| Remove the containers | `docker compose down` | kept (in a Docker volume) |
| Start from an empty database | `docker compose down -v` | **deleted** |

## Configuration

Every setting has a local default, so nothing needs to be set to run locally. To override, copy `.env.example` to `.env` and edit it.

- `docker compose` reads `.env` automatically.
- `./mvnw spring-boot:run` does **not**. Load it into your shell first:
  ```
  set -a; source .env; set +a
  ./mvnw spring-boot:run
  ```
  Otherwise the database and the service end up with different passwords.

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `Port 8080 was already in use` | another app is on 8080 | find it with `lsof -iTCP:8080 -sTCP:LISTEN`, then stop it, or run on another port: `./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=8081` |
| `docker compose up` fails with `port is already allocated` on 5432 | another PostgreSQL (or old container) is using 5432 | `docker ps` to find it, then stop it |
| Health check works but the numbers look wrong | you may be talking to a different app (an old container on the same port) | `docker ps` and `lsof -iTCP:8080 -sTCP:LISTEN` to confirm what is answering |
| `password authentication failed for user "vitallink"` | `.env` changed the password for Docker but the service didn't load it, or an old database volume has a different password | load `.env` as shown above, or reset with `docker compose down -v` |
| `Connection refused` to localhost:5432 | the database isn't running or isn't healthy yet | `docker compose ps`, then wait for `(healthy)` |
| Tests fail with `Could not find a valid Docker environment` | Docker isn't running | start Docker and re-run |
| Startup fails with `Migration checksum mismatch` | a migration file that already ran was edited | never edit an applied migration; add a new one. Locally, `docker compose down -v` resets the database |
| Build fails at `spotless:check` | code isn't formatted | `./mvnw spotless:apply` |
