# Environment Variables

This document lists all environment variables for running the ISMD Tool Backend application.

## Spring Profiles

### `SPRING_PROFILES_ACTIVE`
- **Required**: No
- **Description**: Active Spring profile(s)
- **Default**: `default` (uses `application.properties`)
- **Possible values**: `local`, `stage`, `production`
- **Example**: `SPRING_PROFILES_ACTIVE=local`

## Setting Environment Variables

### Local Development (Windows)
```cmd
set SPRING_PROFILES_ACTIVE=local
mvnw spring-boot:run
```

### Local Development (Linux/Mac)
```bash
export SPRING_PROFILES_ACTIVE=local
./mvnw spring-boot:run
```

### Docker
```yaml
# docker-compose.yml
services:
  ismd-backend:
    environment:
      - SPRING_PROFILES_ACTIVE=stage
```

### Kubernetes
```yaml
# deployment.yaml
env:
  - name: SPRING_PROFILES_ACTIVE
    value: "production"
```

### Azure App Service
```bash
az webapp config appsettings set \
  --name ismd-tool-backend \
  --resource-group ismd-rg \
  --settings SPRING_PROFILES_ACTIVE=production
```

## Keycloak Configuration (Optional Override)

While Keycloak is primarily configured via `application-*.properties`, you can override settings via environment variables if needed:

### `KEYCLOAK_ISSUER_URI`
- **Required**: No (configured in properties files)
- **Description**: Keycloak realm issuer URI
- **Example**: `https://keycloak.dia.gov.cz/realms/ismd`

### `KEYCLOAK_JWK_SET_URI`
- **Required**: No (configured in properties files)
- **Description**: JWK Set URI for token validation
- **Example**: `https://keycloak.dia.gov.cz/realms/ismd/protocol/openid-connect/certs`

**Note:** It's recommended to configure Keycloak URLs in `application-*.properties` files rather than environment variables for better version control and consistency across environments.

## Database Configuration (Optional Override)

### `DB_URL`
- **Description**: PostgreSQL database URL
- **Example**: `jdbc:postgresql://db-server:5432/ismd_tool_db`

### `DB_USERNAME`
- **Description**: Database username
- **Example**: `ismd_user`

### `DB_PASSWORD`
- **Description**: Database password
- **Example**: `secure_password_here`

## Security Best Practices

1. ⚠️ **NEVER commit environment variable values to git**
2. ⚠️ **Use secret management systems** (Azure Key Vault, AWS Secrets Manager, etc.)
3. ⚠️ **Use different credentials** for each environment (local, stage, production)
4. ✅ **Document** which variables are required for each environment
5. ✅ **Use strong passwords** for production databases
6. ✅ **Rotate credentials regularly** in production

## CAAIS Identity Provider Configuration

Keycloak uses CAAIS as an external Identity Provider (IdP). The integration uses mTLS (mutual TLS) via an nginx proxy for token exchange.

### Required Variable

| Variable | Description | Required |
|----------|-------------|----------|
| `CAAIS_CLIENT_ID` | OAuth2 client ID registered with CAAIS | Yes |

### Setup

1. Copy `.env.example` to `.env`
2. Set your CAAIS client ID in `.env`:
   ```
   CAAIS_CLIENT_ID=your-actual-client-id
   ```

### Certificate Setup

CAAIS uses mTLS for authentication (no client secret). Place your certificates in:
- `docker/keycloak/secrets/certifikate.crt` - Client certificate from CAAIS
- `docker/keycloak/secrets/private.key` - Your private key

These files are gitignored for security.

### Architecture

The mTLS authentication is handled by the `nginx-mtls` service, which proxies token requests to CAAIS with the client certificate. CAAIS endpoints are configured in `docker/keycloak/ismd-realm.json`.

For detailed setup instructions, see `KEYCLOAK_SETUP.md`.
