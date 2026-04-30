# Docker Instructions

## Local Development Setup

There are two ways to run the tool backend locally:

### Option A: Native (backend developers)

Run infra dependencies in Docker, Spring Boot directly on the host:

```bash
# Start Postgres, Fuseki, Keycloak
docker-compose up -d

# Run the backend
./mvnw spring-boot:run
```

### Option B: Full backend in Docker (frontend developers)

Run everything in Docker — no JDK required.

#### Prerequisites

1. **Copy and fill in `.env`:**
   ```bash
   cp .env.example .env
   ```
   Required values:
   - `GITHUB_TOKEN` — GitHub PAT with `read:packages` scope (generate at https://github.com/settings/tokens)
   - `GITHUB_ACTOR` — your GitHub username
   - `KEYCLOAK_CLIENT_SECRET` — client secret from the local Keycloak admin UI (`http://localhost:8080`, realm `ismd`, client `ismd-backend`, Credentials tab)

2. **First run builds the backend image from source** (~1-2 min).

#### Start

**Windows (PowerShell):**
```powershell
.\full-backend.ps1 up
# Force rebuild after code changes:
.\full-backend.ps1 up --build
```

**Linux/macOS:**
```bash
chmod +x full-backend.sh
./full-backend.sh up
# Force rebuild after code changes:
./full-backend.sh up --build
```

#### Check it's running

```bash
docker compose --profile full-backend ps
docker logs ismd-tool-backend -f
```

Look for `Started IsmdToolBackendApplication` in the logs.

#### Connect the frontend

In `tool-frontend/.env.local`:
```
BE_URL=http://host.docker.internal:8081/popisujeme
```
Prefer `host.docker.internal` over `localhost` / `127.0.0.1` — it works whether `npm run dev` runs in WSL, PowerShell, or Git Bash, and whether the backend is in Docker or IDEA. (`localhost` resolves to `::1` on newer Node and breaks against IPv4-only backends.) Linux + Docker Engine only (no Docker Desktop)? Use `127.0.0.1` instead — `host.docker.internal` is provided by Docker Desktop and won't resolve on native Docker Engine without extra config.

Then run the frontend:
```bash
cd ../tool-frontend
npm run dev
```

#### Stop

```bash
# PowerShell
.\full-backend.ps1 down
# Bash
./full-backend.sh down
# Or directly:
docker compose --profile full-backend down
```
