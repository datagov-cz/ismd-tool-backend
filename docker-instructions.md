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
   Set `GITHUB_TOKEN` (GitHub PAT with `read:packages` scope — generate at https://github.com/settings/tokens) and `GITHUB_ACTOR` (your GitHub username).

2. **First run builds the backend image from source** (~1-2 min).

#### Start

**Windows (PowerShell):**
```powershell
.\start-full-backend.ps1
# Force rebuild after code changes:
.\start-full-backend.ps1 --build
```

**Linux/macOS:**
```bash
chmod +x start-full-backend.sh
./start-full-backend.sh
# Force rebuild after code changes:
./start-full-backend.sh --build
```

#### Check it's running

```bash
docker-compose --profile full-backend ps
docker logs ismd-tool-backend -f
```

Look for `Started IsmdToolBackendApplication` in the logs.

#### Connect the frontend

In `tool-frontend/.env.local`:
```
BE_URL=http://localhost:8081/popisujeme
```

Then run the frontend:
```bash
cd ../tool-frontend
npm run dev
```

#### Stop

```bash
docker-compose --profile full-backend down
```
