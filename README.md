# ISMD Tool - Local Development Setup

## Prerequisites

- Docker and Docker Compose installed
- Project cloned locally

## Quick Start

### 1. Start All Services

Navigate to the project directory and run:

```bash
docker-compose up -d
```

This will start:
- **PostgreSQL** database on port `5432`
- **Apache Jena Fuseki** triplestore on port `3030`

### 2. Verify Services are Running

Check that both containers are healthy:

```bash
docker-compose ps
```

You should see both `ismd-postgres-dev` and `fuseki` containers running.

## Accessing PostgreSQL Database

### Option 1: Using pgAdmin4

1. Open pgAdmin4
2. Add a new server with these settings:
   - **Host**: `localhost`
   - **Port**: `5432`
   - **Database**: `ismd_tool_db`
   - **Username**: `ismd_user`
   - **Password**: `ismd_password`

### Option 2: Using Command Line

Connect directly via psql:

```bash
docker exec -it ismd-postgres-dev psql -U ismd_user -d ismd_tool_db
```

### Option 3: Using Database IDE

Connect using any PostgreSQL client (DBeaver, DataGrip, etc.) with:
- **Host**: `localhost:5432`
- **Database**: `ismd_tool_db`
- **Username**: `ismd_user`
- **Password**: `ismd_password`

## Accessing Apache Jena Fuseki

### Fuseki Web Interface

1. Open your browser and go to: http://localhost:3030
2. Login with:
   - **Username**: `admin`
   - **Password**: `admin123`

### Dataset Information

- **Dataset Name**: `ismd-tool-dataset`
- **SPARQL Query Endpoint**: http://localhost:3030/ismd-tool-dataset/sparql
- **SPARQL Update Endpoint**: http://localhost:3030/ismd-tool-dataset/update
- **Graph Store Protocol**: http://localhost:3030/ismd-tool-dataset/data

## Application Configuration

Make sure your Spring Boot application is configured to connect to these services:

### Database Configuration
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/ismd_tool_db
    username: ismd_user
    password: ismd_password
```

### Fuseki Configuration
```yaml
fuseki:
  endpoint: http://localhost:3030/ismd-tool-dataset
```

## Managing Services

### Stop Services
```bash
docker-compose down
```

### Stop and Remove All Data
```bash
docker-compose down -v
```
⚠️ **Warning**: This will delete all data in both PostgreSQL and Fuseki!

### View Logs
```bash
# View all logs
docker-compose logs

# View specific service logs
docker-compose logs postgres
docker-compose logs fuseki
```

### Restart Services
```bash
docker-compose restart
```

## Troubleshooting

### PostgreSQL Issues

**Connection refused**: 
- Check if port 5432 is already in use: `lsof -i :5432`
- Wait for the health check to pass (may take 30-60 seconds on first start)

**Permission denied**:
- Check Docker has permission to create volumes
- Try `docker-compose down -v` and `docker-compose up -d`

### Fuseki Issues

**Fuseki not accessible**:
- Check if port 3030 is available: `lsof -i :3030`
- Verify the configuration file `fuseki-config.ttl` exists
- Check logs: `docker-compose logs fuseki`

**Dataset not found**:
- The dataset `ismd-tool-dataset` is automatically configured
- Check the Fuseki admin interface at http://localhost:3030

### General Issues

**Services won't start**:
```bash
# Check Docker daemon is running
docker --version

# Check docker-compose file syntax
docker-compose config

# Check available resources
docker system df
```

## Data Persistence

- **PostgreSQL data** is persisted in the `postgres_data` Docker volume
- **Fuseki/TDB2 data** is persisted in the `./data/tdb2` directory
- Both will survive container restarts unless explicitly removed

## Development Workflow

1. Start services: `docker-compose up -d`
2. Run your Spring Boot application
3. Upload ontologies through your application
4. Query data via Fuseki web interface or SPARQL endpoints
5. View metadata in PostgreSQL via pgAdmin
