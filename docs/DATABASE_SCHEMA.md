# Database Schema Documentation

## Overview

**Database**: PostgreSQL
**Schema Name**: `ismd_schema`
**DDL Management**: Liquibase (`db/changelog/`); Hibernate runs `ddl-auto=validate` on every profile, so an entity/schema mismatch fails startup rather than altering the database
**Auditing**: Enabled via JPA Auditing
**Application**: ISMD Tool Backend - Ontology and Semantic Data Management System

> **Coverage note.** The table definitions below cover `ontologies`, `concepts`, `comments`,
> `validation_reports` and the three diagram tables. `outbox_entry` and `nkd_concept_snapshots` are
> **not yet documented here** — see [`PG_TDB2_CONSISTENCY.md`](./PG_TDB2_CONSISTENCY.md) and
> [`NKD_LOCAL_COPY_SNAPSHOT.md`](./NKD_LOCAL_COPY_SNAPSHOT.md) for those. The `comments` entry below
> also predates changeset `010`, which replaced its `ontology_iri`/`concept_iri` string locators with
> FK columns.

---

## Entity-Relationship Diagram

```
┌─────────────────────────┐
│  ontologies             │
├─────────────────────────┤
│ PK id (BIGINT)          │
│ UK slug (VARCHAR)       │
│    graph_name           │
│    user_id              │
│    is_published         │
│    created_at           │
│    updated_at           │
└──────────┬──────────────┘
           │
           │ 1:N (CASCADE ALL)
           │
           ▼
┌─────────────────────────────┐
│  concepts                   │
├─────────────────────────────┤
│ PK id (BIGINT)              │
│ UK slug (VARCHAR)           │
│ UK concept_iri (VARCHAR)    │
│    concept_name             │
│    concept_type (ENUM)      │
│    graph_name               │
│    user_id                  │
│    is_published             │
│    in_tezaurus              │
│ FK ontology_metadata_id     │
│    created_at               │
│    updated_at               │
└─────────────────────────────┘

┌─────────────────────────┐        ┌──────────────────────────────┐
│  comments               │        │  validation_reports          │
├─────────────────────────┤        ├──────────────────────────────┤
│ PK id (BIGINT)          │        │ PK id (BIGINT)               │
│    comment              │        │    user_id                   │
│    user_id              │        │    ontology_metadata_id      │
│    ontology_iri         │        │    timestamp                 │
│    concept_iri          │        │    results_json (TEXT)       │
│    posted_time          │        │    ontology_iri (TEXT)       │
└─────────────────────────┘        └──────────────────────────────┘
```

---

## Table Definitions

### 1. `ontologies`

**Purpose**: Stores metadata for ontology definitions (vocabularies/semantic models)

**Entity Class**: `com.dia.ismdtoolbackend.entity.OntologyMetadataEntity`

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | BIGINT | PK, AUTO_INCREMENT | Unique identifier |
| `slug` | VARCHAR | NOT NULL, UNIQUE | URL-friendly identifier |
| `graph_name` | VARCHAR | | RDF graph IRI |
| `user_id` | VARCHAR | | Owner's user ID |
| `is_published` | BOOLEAN | | Publication status |
| `created_at` | TIMESTAMP | NOT NULL, NOT UPDATABLE | Creation timestamp (auto-managed) |
| `updated_at` | TIMESTAMP | | Last modification timestamp (auto-managed) |

**Indexes**:
- Primary key on `id`
- Unique index on `slug`

**Relationships**:
- **One-to-Many** with `concepts` (CASCADE ALL, orphan removal enabled)

**Repository**: `OntologyMetadataRepository`

**Query Methods**:
- `findByGraphName(String graphName)`
- `findByGraphNameAndUserId(String graphName, String userId)`
- `findAllByUserId(String userId)`
- `findAllByIsPublished(Boolean isPublished)`
- `findAllByUserIdAndIsPublished(String userId, Boolean isPublished)`
- `findBySlug(String slug)`
- `findBySlugIn(List<String> slugs)`

---

### 2. `concepts`

**Purpose**: Stores metadata for semantic concepts (classes, properties, relationships) within ontologies

**Entity Class**: `com.dia.ismdtoolbackend.entity.ConceptMetadataEntity`

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | BIGINT | PK, AUTO_INCREMENT | Unique identifier |
| `slug` | VARCHAR | NOT NULL, UNIQUE | URL-friendly identifier |
| `concept_iri` | VARCHAR | UNIQUE | Full IRI of the concept |
| `concept_name` | VARCHAR | | Human-readable name |
| `concept_type` | VARCHAR(ENUM) | | Type: TRIDA/VLASTNOST/VZTAH |
| `graph_name` | VARCHAR | | RDF graph IRI |
| `user_id` | VARCHAR | | Owner's user ID |
| `is_published` | BOOLEAN | | Publication status |
| `in_tezaurus` | BOOLEAN | | Whether included in thesaurus |
| `ontology_metadata_id` | BIGINT | FK, NOT NULL | Reference to parent ontology |
| `created_at` | TIMESTAMP | NOT NULL, NOT UPDATABLE | Creation timestamp (auto-managed) |
| `updated_at` | TIMESTAMP | | Last modification timestamp (auto-managed) |

**Indexes**:
- Primary key on `id`
- Unique indexes on `slug` and `concept_iri`

**Foreign Keys**:
- `ontology_metadata_id` → `ontologies(id)` (NOT NULL, LAZY fetch)

**Enum Values** (`concept_type`):
- `TRIDA` - Class/Entity type (Czech: "třída")
- `VLASTNOST` - Property/Attribute type (Czech: "vlastnost")
- `VZTAH` - Relationship type (Czech: "vztah")

**Repository**: `ConceptMetadataRepository`

**Query Methods**:
- `findByConceptIri(String conceptIri)`
- `findBySlug(String slug)`
- `findByGraphName(String graphName)`
- `findByOntologyMetadataId(Long ontologyMetadataId)`
- `findAllByUserIdAndIsPublished(String userId, Boolean isPublished)`
- `findAllByUserId(String userId)`
- `findAllByIsPublished(Boolean isPublished)`

---

### 3. `comments`

**Purpose**: Stores user comments on ontologies and concepts

**Entity Class**: `com.dia.ismdtoolbackend.entity.CommentEntity`

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | BIGINT | PK, AUTO_INCREMENT | Unique identifier |
| `comment` | VARCHAR/TEXT | | Comment content |
| `user_id` | VARCHAR | | Author's user ID |
| `ontology_iri` | VARCHAR | | IRI of commented ontology (optional) |
| `concept_iri` | VARCHAR | | IRI of commented concept (optional) |
| `posted_time` | TIMESTAMP | | When comment was posted |

**Indexes**:
- Primary key on `id`

**Note**: Uses IRI-based soft references (no FK constraints to allow flexibility)

**Repository**: `CommentRepository`

**Query Methods**:
- `findByOntologyIRI(String ontologyIRI)`
- `findByConceptIRI(String conceptIRI)`

---

### 4. `validation_reports`

**Purpose**: Stores SHACL/SKOS validation results for ontologies

**Entity Class**: `com.dia.ismdtoolbackend.entity.ValidationReportEntity`

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | BIGINT | PK (manual) | Report identifier |
| `user_id` | VARCHAR | | User who requested validation |
| `ontology_metadata_id` | BIGINT | | Reference to ontology (soft) |
| `timestamp` | TIMESTAMP | | When validation was performed |
| `results_json` | TEXT | | JSON-serialized validation results |
| `ontology_iri` | TEXT | | IRI of validated ontology |

**Indexes**:
- Primary key on `id`

**Special Features**:
- Manual ID assignment (not auto-generated)
- JSON serialization/deserialization for complex validation results using Jackson ObjectMapper
- No formal FK relationship (soft reference for flexibility)

**Repository**: `ValidationReportRepository`

**Query Methods**:
- `findByOntologyMetadataId(Long id)`

---

### 5. `diagrams`

**Purpose**: One ReactFlow canvas per ontology — presentation data only (viewport + the node/edge children). Never stores concept content; nodes reference concepts by IRI and are joined to live PG/RDF on read.

**Entity Class**: `com.dia.ismdtoolbackend.entity.DiagramEntity`

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | BIGINT | PK, AUTO_INCREMENT | Unique identifier |
| `ontology_metadata_id` | BIGINT | NOT NULL, FK → `ontologies(id)` ON DELETE CASCADE, UNIQUE | The ontology this canvas visualizes — also its **only ownership record** |
| `viewport_x` | DOUBLE PRECISION | | Saved pan X; null until first save |
| `viewport_y` | DOUBLE PRECISION | | Saved pan Y |
| `viewport_zoom` | DOUBLE PRECISION | | Saved zoom |
| `version` | BIGINT | NOT NULL, DEFAULT 0 | `@Version` optimistic lock for concurrent layout saves |
| `created_at` | TIMESTAMP | NOT NULL | Creation time |
| `updated_at` | TIMESTAMP | | Last modification |

**Indexes / Constraints**:
- Primary key on `id`
- `uq_diagrams_ontology_metadata` UNIQUE on `ontology_metadata_id` — one canonical diagram per ontology

**Notes**:
- **No owner column.** A diagram belongs to whoever owns its ontology, reached through the NOT NULL FK; write paths authorize with `belongsToUserBySlug` against the ontology. A denormalized `user_id` existed until changeset `014` dropped it as unused.
- The row is created by the **first write**, never by a read — `GET …/detail` is read-only and serves an unsaved in-memory stand-in for an ontology with no diagram.
- `@Version` only bumps when a `diagrams` column changes, so the save path must call `touch()` (or take `OPTIMISTIC_FORCE_INCREMENT`) when only child nodes/edges changed.

**Repository**: `DiagramRepository`

---

### 6. `diagram_nodes`

**Purpose**: One node on the canvas — its position and, optionally, a staged structural edit ("overlay") not yet applied to RDF.

**Entity Class**: `com.dia.ismdtoolbackend.entity.DiagramNodeEntity`

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | BIGINT | PK, AUTO_INCREMENT | Unique identifier |
| `diagram_id` | BIGINT | NOT NULL, FK → `diagrams(id)` ON DELETE CASCADE | Owning diagram |
| `backing` | VARCHAR(50) | NOT NULL | Node kind; `ISMD_CONCEPT` (every node references a materialized concept) |
| `concept_iri` | VARCHAR(1024) | NOT NULL | The concept this node renders |
| `pos_x` / `pos_y` | DOUBLE PRECISION | NOT NULL | Canvas position |
| `collapsed` | BOOLEAN | NOT NULL | Group collapse state |
| `hidden` | BOOLEAN | NOT NULL | Visibility |
| `parent_node_id` | BIGINT | | Grouping parent (nullable) |
| `pending_edit_json` | VARCHAR (unbounded) | | Staged structural overlay, serialized `DiagramPendingEdit`; null when nothing is staged. Entity declares `columnDefinition = "text"` — the same type in Postgres |

**Indexes / Constraints**:
- Primary key on `id`
- `idx_diagram_nodes_diagram_id` on `diagram_id`
- `uq_diagram_nodes_diagram_concept` UNIQUE on (`diagram_id`, `concept_iri`) — plain, not partial (changeset `013`)
- `ck_diagram_nodes_backing_content` CHECK — `backing = 'ISMD_CONCEPT' AND concept_iri IS NOT NULL`

**Repository**: `DiagramNodeRepository`

---

### 7. `diagram_edges`

**Purpose**: Diagram-owned edges. In the current model edges are **projections** computed from live concept RDF (⊕ overlay) on read, so this table stays empty in normal operation; it exists for edges a diagram would own outright.

**Entity Class**: `com.dia.ismdtoolbackend.entity.DiagramEdgeEntity`

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | BIGINT | PK, AUTO_INCREMENT | Unique identifier |
| `diagram_id` | BIGINT | NOT NULL, FK → `diagrams(id)` ON DELETE CASCADE | Owning diagram |
| `source_node_id` | BIGINT | NOT NULL, FK → `diagram_nodes(id)` ON DELETE CASCADE | Edge source |
| `target_node_id` | BIGINT | NOT NULL, FK → `diagram_nodes(id)` ON DELETE CASCADE | Edge target |
| `edge_kind` | VARCHAR(50) | NOT NULL | `DOMAIN` · `RANGE` · `SUBCLASS_OF` · `SUB_PROPERTY` · `SUB_RELATION` · `EXACT_MATCH` |
| `source_handle` | VARCHAR(255) | | ReactFlow source handle |
| `target_handle` | VARCHAR(255) | | ReactFlow target handle |

**Indexes**:
- Primary key on `id`
- `idx_diagram_edges_diagram_id`, `idx_diagram_edges_source_node_id`, `idx_diagram_edges_target_node_id`

**Repository**: `DiagramEdgeRepository`

**Related docs**: [`DIAGRAM_LAYER.md`](./DIAGRAM_LAYER.md) (architecture), [`DIAGRAM_LAYER_API.md`](./DIAGRAM_LAYER_API.md) (REST contract).

---

## Key Database Features

### Auditing

**Enabled on**: `ontologies`, `concepts`

Auto-managed timestamp fields:
- `created_at` - Set once on creation (immutable via `@CreatedDate`)
- `updated_at` - Updated on every modification (via `@LastModifiedDate`)

**Configuration**:
- Annotation: `@EnableJpaAuditing`
- Listener: `@EntityListeners(AuditingEntityListener.class)`
- Configuration class: `JpaAuditingConfig`

### Cascade Behavior

**Ontology → Concepts**:
- `CascadeType.ALL` - All JPA operations cascade to child concepts
- `orphanRemoval = true` - Deleting an ontology automatically removes all associated concepts
- `FetchType.LAZY` - Concepts are loaded only when accessed

**Example**: When an ontology is deleted, all its concepts are automatically deleted from the database.

### Naming Conventions

- **Tables**: Lowercase plural with underscores (snake_case)
  - Examples: `ontologies`, `concepts`, `comments`, `validation_reports`
- **Columns**: Snake_case with descriptive names
  - Examples: `graph_name`, `user_id`, `is_published`, `created_at`
- **Booleans**: Prefixed with `is_` or `in_`
  - Examples: `is_published`, `is_public`, `in_tezaurus`
- **IDs**: Suffixed with `_id`
  - Examples: `user_id`, `ontology_metadata_id`

### Multi-tenancy Pattern

**User Isolation**: Most entities include `user_id` field for ownership tracking

This enables:
- User-specific queries (e.g., "show me my ontologies")
- Access control (users can only modify their own resources)
- Admin override (admins can access/modify any resource)

### Soft vs Hard References

**Hard References** (Enforced FK constraints):
- `concepts.ontology_metadata_id` → `ontologies.id`

**Soft References** (No FK constraints):
- `comments.ontology_iri` - References ontologies by IRI string
- `comments.concept_iri` - References concepts by IRI string
- `validation_reports.ontology_metadata_id` - References ontologies by ID
- `validation_reports.ontology_iri` - References ontologies by IRI

**Rationale**: Soft references provide flexibility for:
- Cross-system references (RDF IRIs may exist outside this database)
- Historical data preservation (comments can remain even if ontology is deleted)
- External validation services

---

## Constraints Summary

### Primary Keys

All entities use auto-generated identity strategy except:
- `validation_reports` - Manual ID assignment

**Strategy**: `@GeneratedValue(strategy = GenerationType.IDENTITY)`

### Unique Constraints

| Table | Column | Purpose |
|-------|--------|---------|
| `ontologies` | `slug` | URL-friendly unique identifier |
| `concepts` | `slug` | URL-friendly unique identifier |
| `concepts` | `concept_iri` | RDF IRI must be unique |

### Foreign Keys

| Child Table | Column | Parent Table | Parent Column | Constraints |
|-------------|--------|--------------|---------------|-------------|
| `concepts` | `ontology_metadata_id` | `ontologies` | `id` | NOT NULL, CASCADE ALL, LAZY |

### Not Null Constraints

| Table | Columns |
|-------|---------|
| `ontologies` | `slug`, `created_at` |
| `concepts` | `slug`, `ontology_metadata_id`, `created_at` |

---

## Data Storage Strategy

### Dual Storage Architecture

The application uses a **hybrid storage approach** combining relational and semantic databases:

#### 1. PostgreSQL (Relational Database)
**Purpose**: Stores metadata, access control, and application state

**Stored Data**:
- Ontology metadata (owner, publication status, timestamps)
- Concept metadata (type, name, slug)
- User comments and collaboration data
- Validation reports and results
- Access control information (user_id fields)

**Benefits**:
- Fast queries for filtering and access control
- ACID transactions
- Efficient joins and aggregations
- User-friendly queries for application logic

#### 2. Apache Jena TDB2 (RDF Triple Store)
**Purpose**: Stores full semantic/ontological data

**Stored Data**:
- Complete RDF triples for ontologies
- Semantic concept definitions (classes, properties, relationships)
- Ontology relationships and hierarchies
- Full SKOS/OWL vocabulary data
- Multi-language labels and descriptions

**Benefits**:
- Native RDF/SPARQL support
- Semantic reasoning capabilities
- Standards-compliant RDF storage
- Efficient graph traversal

#### Linking the Two Systems

**Graph Name**: The `graph_name` field in PostgreSQL tables contains the RDF graph IRI, which serves as the link to the triple store.

**IRIs**: Concept and ontology IRIs (`concept_iri`, `ontology_iri`) provide direct references to RDF resources.

**Workflow Example**:
1. User creates ontology → Metadata stored in `ontologies` table
2. RDF triples generated → Stored in Fuseki with `graph_name` as graph IRI
3. User queries ontology list → Fast PostgreSQL query on metadata
4. User views ontology details → Combined query: metadata from PostgreSQL + full RDF from Fuseki

---

## Repository Layer

### JPA Repositories

All repositories extend `JpaRepository<Entity, Long>` providing:
- Standard CRUD operations
- Pagination and sorting
- Custom query methods using Spring Data JPA naming conventions

### Custom Query Methods

Query methods follow Spring Data JPA conventions:
- `findBy...` - Single result or Optional
- `findAllBy...` - List of results
- Combined filters using `And`

**Examples**:
```java
// Find by single field
findBySlug(String slug)

// Find with multiple filters
findAllByUserIdAndIsPublished(String userId, Boolean isPublished)

// Find by collection
findBySlugIn(List<String> slugs)
```

---

## Database Configuration

### Connection Properties

**Configuration Source**: `application.properties` / `application-{profile}.properties`

**Key Properties**:
```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/ismd_db
spring.datasource.username=${DB_USERNAME}
spring.datasource.password=${DB_PASSWORD}
spring.jpa.hibernate.ddl-auto=update
spring.jpa.properties.hibernate.default_schema=ismd_schema
```

### Hibernate Configuration

**DDL Auto**: `update` (auto-creates/updates schema based on entities)

**Dialect**: PostgreSQL dialect (auto-detected)

**Show SQL**: Configurable per environment

---

## Migration Strategy

### Current Approach

**Tool**: None (Hibernate auto-update)

**Pros**:
- No manual migration scripts needed
- Schema automatically syncs with entity definitions
- Faster development iteration

**Cons**:
- Less control over schema changes
- Risky for production environments
- No versioned migration history

### Recommended for Production

Consider migration tools for production:
- **Flyway** - Version-based SQL migrations
- **Liquibase** - XML/YAML-based database changesets

**Benefits**:
- Version-controlled schema changes
- Rollback capabilities
- Safer production deployments
- Migration history tracking

---

## Performance Considerations

### Lazy Loading

All relationships use `FetchType.LAZY`:
- `concepts` collection in `OntologyMetadataEntity`

**Benefit**: Prevents N+1 query problems and reduces memory usage

**Note**: Requires transaction context for accessing lazy collections

### Indexing Strategy

**Current Indexes**:
- Primary keys on all `id` columns
- Unique indexes on `slug` fields
- Unique index on `concept_iri`

### Query Optimization

**Repository Methods**: Use Spring Data JPA derived queries for simple filters

---

## Security Considerations

### User Isolation

**Field**: `user_id` present in most entities

**Purpose**:
- Track resource ownership
- Enable user-specific queries
- Support authorization checks

**Implementation**: Populated from JWT token claims during entity creation

### Authorization Checks

**Service Layer**: `OntologySecurityService` provides methods:
- `canModify(ontologyId)` - Checks if user owns ontology or is admin
- `canModifyConcept(conceptId)` - Checks if user owns concept's ontology or is admin
- `canModifyComment(commentId)` - Checks if user owns comment or is admin

**Annotations**: `@PreAuthorize` on controller methods enforces access control

### Data Privacy

**No Sensitive Data**: Database does not store passwords or authentication credentials (handled by Keycloak)

**User IDs**: Stored as strings from OAuth2 JWT `sub` claim

---

## Backup and Recovery

### Recommended Backup Strategy

#### PostgreSQL
- **Full backups**: Daily using `pg_dump`
- **Incremental backups**: WAL archiving for point-in-time recovery
- **Retention**: 30 days minimum

#### Jena Fuseki/TDB2
- **Full backups**: Daily backup of TDB2 data directory
- **Consistency**: Stop writes or use TDB2 backup utilities
- **Retention**: Synchronized with PostgreSQL backups

### Recovery Considerations

**Data Consistency**: Both PostgreSQL and Fuseki must be restored to the same point in time to maintain referential integrity through `graph_name` and IRI references.

---

## Appendix

### Entity Class Locations

| Entity | File Path |
|--------|-----------|
| `OntologyMetadataEntity` | `src/main/java/com/dia/ismdtoolbackend/entity/OntologyMetadataEntity.java` |
| `ConceptMetadataEntity` | `src/main/java/com/dia/ismdtoolbackend/entity/ConceptMetadataEntity.java` |
| `CommentEntity` | `src/main/java/com/dia/ismdtoolbackend/entity/CommentEntity.java` |
| `ValidationReportEntity` | `src/main/java/com/dia/ismdtoolbackend/entity/ValidationReportEntity.java` |

### Repository Class Locations

| Repository | File Path |
|------------|-----------|
| `OntologyMetadataRepository` | `src/main/java/com/dia/ismdtoolbackend/repository/OntologyMetadataRepository.java` |
| `ConceptMetadataRepository` | `src/main/java/com/dia/ismdtoolbackend/repository/ConceptMetadataRepository.java` |
| `CommentRepository` | `src/main/java/com/dia/ismdtoolbackend/repository/CommentRepository.java` |
| `ValidationReportRepository` | `src/main/java/com/dia/ismdtoolbackend/repository/ValidationReportRepository.java` |

### Configuration Class Locations

| Configuration | File Path |
|---------------|-----------|
| `JpaAuditingConfig` | `src/main/java/com/dia/ismdtoolbackend/config/JpaAuditingConfig.java` |
| Database Properties | `src/main/resources/application.properties` |

---

**Document Version**: 1.0
**Last Updated**: 2025-12-31
**Author**: Richard Koubek (generated)
**Project**: ISMD Tool Backend