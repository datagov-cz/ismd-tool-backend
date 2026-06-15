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
   Required for the Keycloak login flow:
   - `KEYCLOAK_CLIENT_SECRET` — client secret from the local Keycloak admin UI (`http://localhost:8080`, realm `ismd`, client `ismd-backend`, Credentials tab)

   Required **only if you build the backend image locally** (see below):
   - `GITHUB_TOKEN` — GitHub PAT with `read:packages` scope (generate at https://github.com/settings/tokens)
   - `GITHUB_ACTOR` — your GitHub username

2. **By default, all images are pulled from GHCR** (backend, fuseki, etc.).
   First run pulls ~a few hundred MB; subsequent runs are instant.

> **Apple Silicon (arm64) note:** the pre-built backend image is published
> multi-arch (amd64 + arm64) as of the multi-arch CI change. If you are on an
> older image tag that predates it and `up` fails with
> `no matching manifest for linux/arm64`, build the image from local source
> instead (see "Build the backend image from local source" below). The Fuseki
> image is amd64-only and runs under emulation on Apple Silicon — fine for dev.

#### Start (default — pull from GHCR)

**Windows (PowerShell):**
```powershell
.\full-backend.ps1 up
```

**Linux/macOS:**
```bash
chmod +x full-backend.sh
./full-backend.sh up
```

#### Build the backend image from local source

Use the build override when working on backend code:

```bash
docker compose -f docker-compose.yml \
               -f docker-compose.build.yml \
               --profile full-backend up --build
```

Builds the backend, pulls everything else (postgres, keycloak, fuseki).
The `GITHUB_TOKEN` is passed to the build via a BuildKit secret mount — it
will not appear in the build log or image history. The Maven dependency
cache is persisted across builds via a BuildKit cache mount, so the second
build is much faster than the first.

#### Rebuild fuseki from local source (rare)

Only needed when modifying the Fuseki Dockerfile or `fuseki-config.ttl`:

```bash
docker compose -f docker-compose.yml \
               -f docker-compose.fuseki-build.yml \
               --profile full-backend up --build
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
