# Keycloak Setup Guide

This guide explains how to set up Keycloak for ISMD Tool Backend authentication and authorization.

## Local Development Setup

### 1. Start Keycloak

Keycloak is included in the docker-compose.yml and will start automatically:

```bash
docker-compose up -d
```

Keycloak will be available at: **http://localhost:8080**

Admin credentials:
- **Username**: `admin`
- **Password**: `admin`

### 2. Access Keycloak Admin Console

1. Open your browser and navigate to http://localhost:8080
2. Click on "Administration Console"
3. Log in with admin credentials

### 3. Create Realm "ismd"

1. In the top-left corner, hover over "Master" and click "Create Realm"
2. Enter realm name: `ismd`
3. Click "Create"

### 4. Create Client for Backend

1. In the left menu, click "Clients"
2. Click "Create client"
3. Configure:
   - **Client ID**: `ismd-app`
   - **Client Protocol**: `openid-connect`
   - **Root URL**: `http://localhost:8081`
4. Click "Next"
5. Configure Capability config:
   - **Client authentication**: `OFF` (public client for now)
   - **Authorization**: `OFF`
   - **Standard flow**: `ON`
   - **Direct access grants**: `ON` (for testing with curl)
   - **Implicit flow**: `OFF`
   - **Service accounts roles**: `OFF`
6. Click "Next"
7. Configure Valid redirect URIs:
   - `http://localhost:8081/*`
   - `http://localhost:3000/*` (if you have a frontend)
8. **Web origins**: `*` (for development; restrict in production)
9. Click "Save"

### 5. Create Roles

1. In the left menu, click "Realm roles"
2. Click "Create role"
3. Create two roles:
   - **ADMIN**: Full access to all resources
     - Name: `ADMIN`
     - Description: `Administrator with full CRUD permissions`
   - **USER**: Default authenticated user
     - Name: `USER`
     - Description: `Regular user with limited permissions`
4. Click "Save" for each role

### 6. Create Test Users

**Note:** The realm configuration (`docker/keycloak/ismd-realm.json`) automatically imports on startup with the following pre-configured user:

**Auto-imported User:**
- **Username**: `testuser`
- **Password**: `password`
- **Role**: `user`

This user is ready to use immediately after starting Keycloak. The manual steps below are **optional** for creating additional users.

---

#### Optional: Create Admin User (Manual)

1. In the left menu, click "Users"
2. Click "Create new user"
3. Configure:
   - **Username**: `admin-user`
   - **Email**: `admin@example.com`
   - **First name**: `Admin`
   - **Last name**: `User`
   - **Email verified**: `ON`
   - **Enabled**: `ON`
4. Click "Create"
5. Go to "Credentials" tab
6. Click "Set password"
7. Set password: `admin123`
8. **Temporary**: `OFF`
9. Click "Save"
10. Go to "Role mapping" tab
11. Click "Assign role"
12. Select both `admin` and `user` roles
13. Click "Assign"

#### Optional: Create Additional Regular User (Manual)

1. Click "Users" → "Create new user"
2. Configure:
   - **Username**: `test-user`
   - **Email**: `user@example.com`
   - **First name**: `Test`
   - **Last name**: `User`
   - **Email verified**: `ON`
   - **Enabled**: `ON`
3. Click "Create"
4. Go to "Credentials" tab
5. Set password: `user123`
6. **Temporary**: `OFF`
7. Go to "Role mapping" tab
8. Assign only `USER` role

## Testing Authentication

### Get Access Token (Password Grant)

```bash
# For testuser (configured in realm auto-import)
curl -X POST http://localhost:8080/realms/ismd/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=ismd-app" \
  -d "client_secret=secret123" \
  -d "username=testuser" \
  -d "password=password" \
  -d "grant_type=password"

# Save token to variable for easy reuse
TOKEN=$(curl -s -X POST http://localhost:8080/realms/ismd/protocol/openid-connect/token \
  -d "client_id=ismd-app" \
  -d "client_secret=secret123" \
  -d "username=testuser" \
  -d "password=password" \
  -d "grant_type=password" \
  | jq -r '.access_token')

echo "Token: $TOKEN"
```

Response will contain:
```json
{
  "access_token": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...",
  "expires_in": 300,
  "refresh_expires_in": 1800,
  "refresh_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "token_type": "Bearer"
}
```

### Test API Endpoints with Token

```bash
# Save the access token
TOKEN="<paste_access_token_here>"

# Test authenticated endpoint (create ontology)
curl -X POST http://localhost:8081/api/ontology/create \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "namespace": "http://example.com/ontology/",
    "nameModel": {"name": "Test Ontology"},
    "descriptionModel": {"description": "Test description"}
  }'

# Test public endpoint (no token needed)
curl http://localhost:8081/api/ontology/1/detail
```

### Decode JWT Token

You can decode the JWT token at https://jwt.io to inspect claims:

Important claims:
- `sub`: User ID (UUID)
- `preferred_username`: Username
- `realm_access.roles`: Array of roles (e.g., ["ADMIN", "USER"])

## Authorization Rules

The application enforces these authorization rules:

| Operation | Anonymous | Authenticated (USER) | Authenticated (Owner) | ADMIN |
|-----------|-----------|---------------------|----------------------|-------|
| **GET** ontologies/concepts | ✓ | ✓ | ✓ | ✓ |
| **CREATE** ontology/concept | ✗ | ✓ | ✓ | ✓ |
| **UPDATE** own ontology/concept | ✗ | ✗ | ✓ | ✓ |
| **UPDATE** other's ontology/concept | ✗ | ✗ | ✗ | ✓ |
| **DELETE** own ontology/concept | ✗ | ✗ | ✓ | ✓ |
| **DELETE** other's ontology/concept | ✗ | ✗ | ✗ | ✓ |

## CAAIS Identity Provider Integration

CAAIS (Centralni autentizacni a autorizacni sluzba) is configured as an external OIDC Identity Provider in Keycloak, allowing users to log in using their government credentials.

### Architecture

```
User → Keycloak Login Page → "Login with CAAIS" button → CAAIS Auth
                                                              ↓
User ← Frontend ← Keycloak JWT ← Keycloak ← nginx-mtls ← CAAIS Token
```

- **nginx-mtls**: Handles mTLS (mutual TLS) authentication with CAAIS token endpoint
- **Keycloak**: Brokers the identity, creates local users, maps roles

### Prerequisites

1. **CAAIS Client ID**: Provided by CAAIS during registration
2. **Client Certificate**: Signed by CAAIS (located at `docker/keycloak/secrets/certifikate.crt`)
3. **Private Key**: Your private key (located at `docker/keycloak/secrets/private.key`)

### Setup Instructions

#### 1. Generate Internal TLS Certificate

Before first start, generate the internal certificate for nginx-mtls:

```bash
# From project root directory
./docker/nginx-mtls/generate-internal-cert.sh
```

This creates self-signed certificates for internal Keycloak-to-nginx communication.

#### 2. Configure CAAIS Client ID

Create a `.env` file in the project root:

```bash
cp .env.example .env
# Edit .env and set your CAAIS_CLIENT_ID
```

Or set the environment variable:
```bash
export CAAIS_CLIENT_ID=your-actual-client-id
```

#### 3. Start Services

```bash
docker-compose up -d
```

Verify nginx-mtls is running:
```bash
docker logs ismd-nginx-mtls
```

### Testing CAAIS Login

1. Open Keycloak login page: http://localhost:8080/realms/ismd/account
2. Click "Prihlasit pres CAAIS" button
3. Authenticate at CAAIS using test credentials
4. Verify redirect back to your application
5. Check Keycloak Admin Console > Users for the new user

### CAAIS Endpoints (Test Environment)

| Purpose | URL |
|---------|-----|
| Authorization | `https://rest-openidconnectapi.caais-test-ext.gov.cz/oauth2/authorize` |
| Token (mTLS) | `https://cert-openidconnectapi.caais-test-ext.gov.cz/oauth2/token` |
| UserInfo | `https://rest-openidconnectapi.caais-test-ext.gov.cz/userinfo` |
| JWKS | `https://rest-openidconnectapi.caais-test-ext.gov.cz/oauth2/jwks` |

### Role Mapping

Currently all CAAIS users are assigned the `user` role by default.

**TODO**: Once CAAIS role codes are known, add mappings for `admin` role in `ismd-realm.json`:
```json
{
  "name": "caais-admin-role",
  "identityProviderAlias": "caais",
  "identityProviderMapper": "oidc-advanced-role-idp-mapper",
  "config": {
    "claims": "[{\"key\":\"access_roles[*].access_role_code\",\"value\":\"ACTUAL_ADMIN_CODE\"}]",
    "role": "admin"
  }
}
```

### Troubleshooting CAAIS Integration

#### nginx-mtls Connection Issues

```bash
# Check nginx logs
docker logs ismd-nginx-mtls

# Test mTLS manually
openssl s_client -connect cert-openidconnectapi.caais-test-ext.gov.cz:443 \
  -cert docker/keycloak/secrets/certifikate.crt \
  -key docker/keycloak/secrets/private.key
```

#### Keycloak IdP Errors

```bash
# Check Keycloak logs
docker logs ismd-keycloak-dev

# Verify IdP config in Admin Console:
# Identity Providers > caais > Settings
```

#### CAAIS Redirects to Error Page

- Verify `redirect_uri` is registered with CAAIS
- Check that `CAAIS_CLIENT_ID` is set correctly
- Ensure certificate matches the one registered with CAAIS

### Disabling CAAIS Integration

To disable CAAIS IdP temporarily:

1. Open Keycloak Admin Console
2. Go to Identity Providers > caais
3. Toggle "Enabled" to OFF
4. Click "Save"

---

## Production Setup

For production deployment:

1. **Use separate Keycloak server** (not in docker-compose)
2. **Update application-production.properties**:
   ```properties
   spring.security.oauth2.resourceserver.jwt.issuer-uri=https://keycloak.dia.gov.cz/realms/ismd
   spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://keycloak.dia.gov.cz/realms/ismd/protocol/openid-connect/certs
   ```
3. **Enable HTTPS** for Keycloak
4. **Configure proper redirect URIs** (production domain)
5. **Restrict CORS origins** in client configuration
6. **Use strong passwords** for admin and users
7. **Enable email verification** for new users
8. **Configure session timeouts** appropriately
9. **Set up monitoring and logging**
10. **Regular security updates** for Keycloak

## Troubleshooting

### Keycloak Container Not Starting

```bash
# Check logs
docker-compose logs keycloak

# Restart Keycloak
docker-compose restart keycloak

# Check PostgreSQL is running (Keycloak needs it)
docker-compose ps postgres
```

### Token Validation Errors

1. **Check issuer-uri** matches realm URL
2. **Verify JWT signature** - must be signed by Keycloak
3. **Check token expiration** - tokens expire after 5 minutes by default
4. **Inspect JWT claims** at https://jwt.io

### Application Can't Connect to Keycloak

1. **Verify Keycloak is running**: http://localhost:8080
2. **Check application-local.properties** has correct URLs
3. **Ensure realm "ismd" exists** in Keycloak
4. **Check network connectivity** between containers

### 401 Unauthorized Errors

- **Missing Authorization header**: Include `Authorization: Bearer <token>`
- **Expired token**: Get a new token
- **Invalid token**: Verify token is from correct realm

### 403 Forbidden Errors

- **User lacks role**: Check user has USER role in Keycloak
- **Ownership check failed**: User trying to modify someone else's resource
- **Admin role required**: Operation requires ADMIN role

## Keycloak Documentation

- Official documentation: https://www.keycloak.org/documentation
- Getting Started: https://www.keycloak.org/getting-started
- Server Admin Guide: https://www.keycloak.org/docs/latest/server_admin/
