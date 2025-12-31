# ISMD Tool Backend - Local Development Setup Guide

## Table of Contents

- [Prerequisites](#prerequisites)
- [Initial Setup](#initial-setup)
- [Starting the Application](#starting-the-application)
- [Service Details](#service-details)
- [Development Workflow](#development-workflow)
- [Testing](#testing)
- [Troubleshooting](#troubleshooting)
- [Useful Commands](#useful-commands)
- [IDE Configuration](#ide-configuration)

---

## Prerequisites

### Required Software

Before you begin, ensure you have the following installed:

| Software | Version | Download Link |
|----------|---------|---------------|
| **Java JDK** | 17 or higher | https://adoptium.net/ |
| **Maven** | 3.8+ | https://maven.apache.org/download.cgi |
| **Docker** | Latest | https://www.docker.com/products/docker-desktop |
| **Docker Compose** | Latest (included with Docker Desktop) | - |
| **Git** | Latest | https://git-scm.com/downloads |

### Optional Tools

- **IntelliJ IDEA** or **Eclipse** (for Java development)
- **Postman** or **Insomnia** (for API testing)
- **pgAdmin** or **DBeaver** (for database management)
- **curl** or **httpie** (for command-line API testing)

### Verify Installation

Run these commands to verify your setup:

```bash
# Check Java version (should be 17+)
java -version

# Check Maven version
mvn -version

# Check Docker version
docker --version
docker-compose --version

# Check Git version
git --version
```

---

## Initial Setup

### 1. Clone the Repository

```bash
git clone <repository-url>
cd ismd-tool-backend
```

### 2. Create Environment Configuration

Create a `.env` file from the example:

```bash
cp .env.example .env
```

Edit `.env` and configure CAAIS client ID (if needed):

```properties
CAAIS_CLIENT_ID=your-client-id-here
```

### 3. Configure Application Properties

The application uses profile-based configuration:

- **`application.properties`** - Common properties and defaults
- **`application-local.properties`** - Local development configuration (active by default)
- **`application-dev.properties`** - Development environment
- **`application-stage.properties`** - Staging environment
- **`application-production.properties`** - Production environment

For local development, the `local` profile is active by default.

### 4. Set Up Docker Services

The application requires three external services:

1. **PostgreSQL** - Relational database for metadata
2. **Apache Jena Fuseki** - RDF triple store for ontology data
3. **Keycloak** - OAuth2/OIDC identity provider

Start all services:

```bash
docker-compose up -d
```

This will start:
- PostgreSQL on `localhost:5432`
- Fuseki on `localhost:3030`
- Keycloak on `localhost:8080`
- Nginx mTLS proxy (for CAAIS integration)

### 5. Verify Services are Running

Check container status:

```bash
docker-compose ps
```

All containers should show status `Up` or `healthy`.

### 6. Install Maven Dependencies

```bash
mvn clean install
```

This will:
- Download all dependencies
- Compile the project
- Run tests
- Build the JAR file

**Note**: This requires access to GitHub Packages for the `ismd-validator-common` dependency. Ensure you have configured Maven authentication in `~/.m2/settings.xml`.

---

## Starting the Application

### Option 1: Using Maven (Recommended for Development)

```bash
mvn spring-boot:run
```

The application will start on **http://localhost:8081/popisujeme**

### Option 2: Using Java JAR

```bash
# Build the JAR
mvn clean package -DskipTests

# Run the JAR
java -jar target/ismd-tool-backend-0.0.1-SNAPSHOT.jar
```

### Option 3: Using IDE

**IntelliJ IDEA:**
1. Open the project
2. Right-click on `IsmdToolBackendApplication.java`
3. Select "Run 'IsmdToolBackendApplication'"

**Eclipse:**
1. Open the project
2. Right-click on the project → Run As → Spring Boot App

### Verify Application Started Successfully

1. Check application health: http://localhost:8081/popisujeme/actuator/health
2. Check API documentation: http://localhost:8081/popisujeme/swagger-ui.html
3. Check logs for startup messages

Expected log output:
```
Started IsmdToolBackendApplication in X.XXX seconds
```

---

## Service Details

### 1. PostgreSQL Database

**Connection Details:**
- **Host**: `localhost`
- **Port**: `5432`
- **Database**: `ismd_tool_db`
- **Username**: `ismd_user`
- **Password**: `ismd_password`
- **Schema**: `ismd_schema`

**Access via Command Line:**
```bash
docker exec -it ismd-postgres-dev psql -U ismd_user -d ismd_tool_db
```

**Common SQL Commands:**
```sql
-- List all tables
\dt ismd_schema.*

-- View ontologies
SELECT * FROM ismd_schema.ontologies;

-- View concepts
SELECT * FROM ismd_schema.concepts;

-- View comments
SELECT * FROM ismd_schema.comments;

-- View validation reports
SELECT * FROM ismd_schema.validation_reports;
```

**Connect via GUI Tool (pgAdmin/DBeaver):**
1. Create new PostgreSQL connection
2. Enter connection details above
3. Connect and browse `ismd_schema` schema

**Database Reset:**
```bash
# Stop and remove all data
docker-compose down -v

# Restart services
docker-compose up -d
```

---

### 2. Apache Jena Fuseki (RDF Triple Store)

**Web Interface:**
- **URL**: http://localhost:3030
- **Username**: `admin`
- **Password**: `admin123`

**Dataset Information:**
- **Dataset Name**: `ismd-tool-dataset`
- **SPARQL Query Endpoint**: http://localhost:3030/ismd-tool-dataset/sparql
- **SPARQL Update Endpoint**: http://localhost:3030/ismd-tool-dataset/update
- **Graph Store Protocol**: http://localhost:3030/ismd-tool-dataset/data

**Data Storage:**
- TDB2 data is persisted in `./data/tdb2/` directory
- Data survives container restarts

**SPARQL Query Examples:**

List all named graphs:
```sparql
SELECT DISTINCT ?g
WHERE {
  GRAPH ?g { ?s ?p ?o }
}
```

List all ontologies:
```sparql
PREFIX owl: <http://www.w3.org/2002/07/owl#>
PREFIX skos: <http://www.w3.org/2004/02/skos/core#>

SELECT ?ontology ?label
WHERE {
  GRAPH ?g {
    ?ontology a owl:Ontology ;
              skos:prefLabel ?label .
  }
}
```

Count triples in a graph:
```sparql
SELECT (COUNT(*) as ?count)
WHERE {
  GRAPH <graph-uri> {
    ?s ?p ?o
  }
}
```

**Backup Fuseki Data:**
```bash
# Create backup
docker exec fuseki curl -X POST http://localhost:3030/$/backup/ismd-tool-dataset

# Manual backup (Fuseki stopped)
cp -r ./data/tdb2 ./backups/tdb2-$(date +%Y%m%d)
```

---

### 3. Keycloak (OAuth2/OIDC Identity Provider)

**Admin Console:**
- **URL**: http://localhost:8080
- **Admin Username**: `admin`
- **Admin Password**: `admin`

**Realm Information:**
- **Realm Name**: `ismd`
- **Client ID**: `ismd-app` (or configured in application.properties)
- **Issuer URI**: http://localhost:8080/realms/ismd

**Key Endpoints:**
- **Authorization**: http://localhost:8080/realms/ismd/protocol/openid-connect/auth
- **Token**: http://localhost:8080/realms/ismd/protocol/openid-connect/token
- **UserInfo**: http://localhost:8080/realms/ismd/protocol/openid-connect/userinfo
- **JWKs**: http://localhost:8080/realms/ismd/protocol/openid-connect/certs
- **Logout**: http://localhost:8080/realms/ismd/protocol/openid-connect/logout

**Create Test User:**

1. Open Keycloak Admin Console: http://localhost:8080
2. Login with admin credentials
3. Select the `ismd` realm
4. Navigate to: Users → Add user
5. Fill in user details:
   - Username: `testuser`
   - Email: `testuser@example.com`
   - First Name: `Test`
   - Last Name: `User`
   - Email Verified: `ON`
   - Enabled: `ON`
6. Click **Save**
7. Go to **Credentials** tab
8. Set password:
   - Password: `password123`
   - Temporary: `OFF`
9. Click **Set Password**

**Assign Admin Role (Optional):**

1. Go to user's **Role Mappings** tab
2. Under **Realm Roles**, assign `ROLE_ADMIN`

**Get Access Token (for API testing):**

```bash
# Get token
curl -X POST http://localhost:8080/realms/ismd/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "username=testuser" \
  -d "password=password123" \
  -d "grant_type=password" \
  -d "client_id=ismd-app" \
  -d "client_secret=secret123"

# Extract access token from response
# Use jq for JSON parsing:
curl -X POST http://localhost:8080/realms/ismd/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "username=testuser" \
  -d "password=password123" \
  -d "grant_type=password" \
  -d "client_id=ismd-app" \
  -d "client_secret=secret123" | jq -r '.access_token'
```

**Use Token in API Requests:**

```bash
# Save token to variable
TOKEN=$(curl -s -X POST http://localhost:8080/realms/ismd/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "username=testuser" \
  -d "password=password123" \
  -d "grant_type=password" \
  -d "client_id=ismd-app" \
  -d "client_secret=secret123" | jq -r '.access_token')

# Make authenticated API request
curl -X GET http://localhost:8081/popisujeme/api/user/me \
  -H "Authorization: Bearer $TOKEN"
```

---

### 4. Nginx mTLS Proxy (CAAIS Integration)

**Purpose**: Provides mTLS (mutual TLS) proxy for connecting to CAAIS identity provider.

**Configuration:**
- Internal TLS certificate (Keycloak ↔ nginx)
- CAAIS client certificate (nginx ↔ CAAIS)

**Certificates Location:**
- `./docker/nginx-mtls/certs/` - Internal certificates
- `./docker/keycloak/secrets/` - CAAIS client certificates

**Health Check:**
```bash
curl -fsk https://localhost:8443/health
```

---

## Development Workflow

### 1. Start Development Session

```bash
# Start all Docker services
docker-compose up -d

# Verify services are healthy
docker-compose ps

# Start the Spring Boot application
mvn spring-boot:run
```

### 2. Make Code Changes

- Edit Java files in `src/main/java/`
- Edit configuration in `src/main/resources/`
- Spring Boot DevTools enables hot reload for most changes

### 3. Test Changes

```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=OntologyServiceTest

# Run specific test method
mvn test -Dtest=OntologyServiceTest#testCreateOntology
```

### 4. Access API Documentation

Open Swagger UI: http://localhost:8081/popisujeme/swagger-ui.html

### 5. Test API Endpoints

**Using Swagger UI:**
1. Open http://localhost:8081/popisujeme/swagger-ui.html
2. Click "Authorize" button
3. Enter Bearer token (obtained from Keycloak)
4. Test endpoints interactively

**Using curl:**

```bash
# Get current user info
curl -X GET http://localhost:8081/popisujeme/api/user/me \
  -H "Authorization: Bearer $TOKEN"

# List ontologies
curl -X GET http://localhost:8081/popisujeme/api/ontology/list

# Create ontology
curl -X POST http://localhost:8081/popisujeme/api/ontology/create \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "namespace": "https://data.dia.gov.cz/zdroj/slovníky/test",
    "nameModel": {
      "name": {
        "cs": "Testovací slovník"
      }
    },
    "descriptionModel": {
      "description": {
        "cs": "Popis testovacího slovníku"
      }
    }
  }'
```

### 6. View Logs

**Application Logs:**
```bash
# Using Maven (terminal output)
mvn spring-boot:run

# Using Docker services
docker-compose logs -f
docker-compose logs -f postgres
docker-compose logs -f fuseki
docker-compose logs -f keycloak
```

**Log Levels (configured in `application-local.properties`):**
- `DEBUG` - Spring Web, Hibernate SQL, custom services
- `TRACE` - Hibernate parameter binding

### 7. Debug Application

**IntelliJ IDEA:**
1. Set breakpoints in code
2. Right-click on `IsmdToolBackendApplication.java`
3. Select "Debug 'IsmdToolBackendApplication'"

**Remote Debugging:**
```bash
# Start application with debug port
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=n,address=5005"

# In IDE, create Remote JVM Debug configuration:
# Host: localhost
# Port: 5005
```

### 8. Database Migrations

The application uses Hibernate's `ddl-auto=update` for automatic schema updates.

**View Generated SQL:**
- Check logs when application starts
- `spring.jpa.show-sql=true` outputs SQL statements
- `spring.jpa.properties.hibernate.format_sql=true` formats SQL

**Manual Schema Inspection:**
```sql
-- Connect to database
docker exec -it ismd-postgres-dev psql -U ismd_user -d ismd_tool_db

-- Show table structure
\d+ ismd_schema.ontologies
\d+ ismd_schema.concepts
\d+ ismd_schema.comments
\d+ ismd_schema.validation_reports
```

---

## Testing

### Run All Tests

```bash
mvn test
```

### Run Tests with Coverage

```bash
mvn test jacoco:report
```

Coverage report: `target/site/jacoco/index.html`

### Test Categories

**Unit Tests:**
- Located in `src/test/java/`
- Test individual components in isolation
- Use mocked dependencies

**Integration Tests:**
- Test service integration with repositories
- Use in-memory H2 database

**API Tests:**
- Test REST endpoints
- Use `@WebMvcTest` or `@SpringBootTest`

### Test Database

Tests use **H2 in-memory database** (configured in test dependencies).

**Configuration:**
- Automatically configured for tests
- No manual setup required
- Database is created and destroyed for each test run

---

## Troubleshooting

### Application Won't Start

**Problem**: Port 8081 already in use

```bash
# Find process using port
lsof -i :8081

# Kill process
kill -9 <PID>
```

**Problem**: Database connection fails

```bash
# Check PostgreSQL is running
docker-compose ps postgres

# Check logs
docker-compose logs postgres

# Restart PostgreSQL
docker-compose restart postgres
```

**Problem**: Fuseki connection fails

```bash
# Check Fuseki is running
docker-compose ps fuseki

# Access Fuseki web interface
open http://localhost:3030

# Restart Fuseki
docker-compose restart fuseki
```

---

### Docker Issues

**Problem**: Containers won't start

```bash
# Check Docker daemon
docker info

# Check docker-compose file syntax
docker-compose config

# Remove old containers and volumes
docker-compose down -v

# Rebuild and start
docker-compose up -d --build
```

**Problem**: Port conflicts

```bash
# Find processes using ports
lsof -i :5432  # PostgreSQL
lsof -i :3030  # Fuseki
lsof -i :8080  # Keycloak

# Change ports in docker-compose.yml if needed
```

**Problem**: Volume permission errors

```bash
# Fix ownership (Linux/macOS)
sudo chown -R $USER:$USER ./data/tdb2

# On Windows, ensure Docker has access to the drive
```

---

### Maven Issues

**Problem**: Dependency download fails

```bash
# Clear Maven cache
mvn dependency:purge-local-repository

# Force update
mvn clean install -U
```

**Problem**: GitHub Packages authentication fails

Configure `~/.m2/settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>github</id>
      <username>YOUR_GITHUB_USERNAME</username>
      <password>YOUR_GITHUB_TOKEN</password>
    </server>
  </servers>
</settings>
```

Generate GitHub Personal Access Token:
1. GitHub → Settings → Developer settings → Personal access tokens
2. Generate new token (classic)
3. Select scope: `read:packages`
4. Copy token and use as password

---

### Keycloak Issues

**Problem**: Cannot access admin console

- Check container is running: `docker-compose ps keycloak`
- Check logs: `docker-compose logs keycloak`
- Verify port 8080 is not in use: `lsof -i :8080`

**Problem**: JWT validation fails

- Check issuer URI in `application-local.properties`
- Ensure Keycloak realm `ismd` exists
- Verify client configuration matches application properties

**Problem**: User authentication fails

- Verify user exists in Keycloak
- Check user credentials
- Ensure user is enabled
- Check realm roles are assigned

---

### Common Error Messages

**Error**: `org.postgresql.util.PSQLException: Connection refused`
- **Solution**: PostgreSQL not running, start with `docker-compose up -d postgres`

**Error**: `Connection to http://localhost:3030 refused`
- **Solution**: Fuseki not running, start with `docker-compose up -d fuseki`

**Error**: `JWT issuer validation failed`
- **Solution**: Check Keycloak is running and issuer URI is correct

**Error**: `Table "ontologies" doesn't exist`
- **Solution**: Schema not created, check `spring.jpa.hibernate.ddl-auto=update` is set

**Error**: `Access denied for user 'ismd_user'`
- **Solution**: Check database credentials in `application-local.properties`

---

## Useful Commands

### Docker Commands

```bash
# Start all services
docker-compose up -d

# Stop all services
docker-compose down

# Stop and remove volumes (deletes all data)
docker-compose down -v

# View logs
docker-compose logs -f

# View specific service logs
docker-compose logs -f postgres
docker-compose logs -f fuseki
docker-compose logs -f keycloak

# Restart services
docker-compose restart

# Rebuild services
docker-compose up -d --build

# Check service status
docker-compose ps

# Execute command in container
docker exec -it ismd-postgres-dev bash
```

### Maven Commands

```bash
# Clean and install
mvn clean install

# Run application
mvn spring-boot:run

# Run tests
mvn test

# Skip tests
mvn clean install -DskipTests

# Run specific test
mvn test -Dtest=OntologyServiceTest

# Package JAR
mvn clean package

# Update dependencies
mvn clean install -U

# Show dependency tree
mvn dependency:tree

# Generate JavaDoc
mvn javadoc:javadoc
```

### Database Commands

```bash
# Connect to PostgreSQL
docker exec -it ismd-postgres-dev psql -U ismd_user -d ismd_tool_db

# Dump database
docker exec ismd-postgres-dev pg_dump -U ismd_user ismd_tool_db > backup.sql

# Restore database
docker exec -i ismd-postgres-dev psql -U ismd_user ismd_tool_db < backup.sql

# View table sizes
docker exec -it ismd-postgres-dev psql -U ismd_user -d ismd_tool_db -c "\
SELECT
  schemaname,
  tablename,
  pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename)) AS size
FROM pg_tables
WHERE schemaname = 'ismd_schema'
ORDER BY pg_total_relation_size(schemaname||'.'||tablename) DESC;"
```

### Fuseki Commands

```bash
# Backup dataset
curl -X POST http://localhost:3030/$/backup/ismd-tool-dataset

# View statistics
curl http://localhost:3030/$/stats/ismd-tool-dataset

# Clear dataset (delete all data)
curl -X POST http://localhost:3030/ismd-tool-dataset/update \
  -H "Content-Type: application/sparql-update" \
  -d "DROP ALL"
```

### Git Commands

```bash
# Check current branch
git branch

# Switch to dev branch
git checkout dev

# Pull latest changes
git pull origin dev

# Create feature branch
git checkout -b feature/my-feature

# Commit changes
git add .
git commit -m "Description of changes"

# Push changes
git push origin feature/my-feature
```

---

## IDE Configuration

### IntelliJ IDEA

**Import Project:**
1. File → Open
2. Select `pom.xml`
3. Open as Project
4. Wait for Maven to download dependencies

**Configure Java SDK:**
1. File → Project Structure → Project
2. Project SDK: Select Java 17
3. Project language level: 17

**Enable Annotation Processing (for Lombok and MapStruct):**
1. Settings → Build, Execution, Deployment → Compiler → Annotation Processors
2. Enable annotation processing: ✓

**Configure Code Style:**
1. Settings → Editor → Code Style → Java
2. Import scheme from project (if available)

**Run Configuration:**
1. Run → Edit Configurations
2. Add New Configuration → Spring Boot
3. Main class: `com.dia.ismdtoolbackend.IsmdToolBackendApplication`
4. Active profiles: `local`
5. VM options (optional): `-Xmx2048m`

**Enable Spring Boot Dashboard:**
1. View → Tool Windows → Services
2. Spring Boot applications will appear here

---

### Eclipse

**Import Project:**
1. File → Import → Maven → Existing Maven Projects
2. Select project root directory
3. Finish

**Configure Java JDK:**
1. Project → Properties → Java Build Path
2. Libraries → Add Library → JRE System Library → Java 17

**Enable Lombok:**
1. Download lombok.jar
2. Run: `java -jar lombok.jar`
3. Select Eclipse installation
4. Install/Update

**Run Application:**
1. Right-click on project → Run As → Spring Boot App

---

### VS Code

**Install Extensions:**
- Extension Pack for Java (Microsoft)
- Spring Boot Extension Pack (VMware)
- Lombok Annotations Support

**Open Project:**
1. File → Open Folder
2. Select project directory
3. Wait for Java extension to activate

**Run Application:**
1. Open `IsmdToolBackendApplication.java`
2. Click "Run" above the main method
3. Or use Command Palette: "Spring Boot: Run"

---

## Performance Tips

### Speed Up Maven Builds

Add to `~/.m2/settings.xml`:

```xml
<settings>
  <localRepository>/path/to/large/disk/.m2/repository</localRepository>
</settings>
```

### Speed Up Application Startup

**Disable unnecessary auto-configuration:**

Create `application-local.properties`:
```properties
spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.admin.SpringApplicationAdminJmxAutoConfiguration
```

**Reduce logging verbosity:**
```properties
logging.level.org.springframework.web=INFO
logging.level.org.hibernate=INFO
```

### Optimize Docker Performance

**Allocate more resources to Docker:**
- Docker Desktop → Settings → Resources
- Increase CPUs: 4+
- Increase Memory: 4GB+

---

## Additional Resources

### Documentation

- **Spring Boot**: https://docs.spring.io/spring-boot/docs/current/reference/html/
- **Spring Security OAuth2**: https://docs.spring.io/spring-security/reference/servlet/oauth2/index.html
- **Apache Jena**: https://jena.apache.org/documentation/
- **Keycloak**: https://www.keycloak.org/documentation
- **PostgreSQL**: https://www.postgresql.org/docs/

### Project-Specific Documentation

- [Database Schema Documentation](./DATABASE_SCHEMA.md)
- [Jena TDB2 RDF Schema Documentation](./JENA_TDB2_RDF_SCHEMA.md)
- [README](./README.md)

### Support

For issues and questions:
- Check existing documentation
- Review application logs
- Check Docker service logs
- Consult team members

---

**Document Version**: 1.0
**Last Updated**: 2025-12-31
**Author**: Richard Koubek
**Project**: ISMD Tool Backend
