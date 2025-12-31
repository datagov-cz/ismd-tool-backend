# Jena TDB2 / Apache Fuseki RDF Schema Documentation

## Overview

**RDF Store**: Apache Jena Fuseki with TDB2 backend
**Jena Version**: 5.3.0
**Access Method**: HTTP SPARQL endpoint
**Dataset**: Named graphs per ontology
**Primary Vocabularies**: SKOS, OWL, RDF, RDFS, DCTerms, OFN (Czech Open Formal Standards)

---

## Architecture Overview

### Dual Storage Strategy

The application uses a **hybrid storage architecture** combining relational and semantic databases:

```
┌─────────────────────────────────────────────────────────────┐
│                    Application Layer                        │
├─────────────────────────────────────────────────────────────┤
│  Controllers → Services → Repositories                      │
└────────────┬─────────────────────────────┬──────────────────┘
             │                             │
             ▼                             ▼
┌────────────────────────┐    ┌───────────────────────────────┐
│   PostgreSQL (Metadata)│    │  Apache Fuseki (RDF Triples)  │
├────────────────────────┤    ├───────────────────────────────┤
│ - Ontology metadata    │    │ - Complete RDF graphs         │
│ - Concept metadata     │    │ - Semantic relationships      │
│ - User/ownership data  │    │ - Multi-language labels       │
│ - Comments             │    │ - Full ontology definitions   │
│ - Validation reports   │    │ - SKOS/OWL structures         │
└────────────────────────┘    └───────────────────────────────┘
         (Access Control)              (Semantic Data)
```

**PostgreSQL**: Fast queries for filtering, access control, publication status
**Jena Fuseki/TDB2**: Complete semantic data with SPARQL query capabilities

---

## Configuration

### Jena/Fuseki Configuration

**Configuration Class**: `com.dia.ismdtoolbackend.config.JenaConfig`

**Application Properties**:

```properties
# Direct TDB2 access (local development)
jena.access.method=direct
jena.tdb2.location=./data/tdb2/production-dataset

# Fuseki HTTP endpoint (deployment)
jena.fuseki.url=${FUSEKI_URL}  # Default: http://localhost:3030/ismd-tool-dataset
```

**Dependencies** (from `pom.xml`):
```xml
<jena.version>5.3.0</jena.version>

<dependency>
    <groupId>org.apache.jena</groupId>
    <artifactId>jena-core</artifactId>
</dependency>
<dependency>
    <groupId>org.apache.jena</groupId>
    <artifactId>jena-arq</artifactId>
</dependency>
<dependency>
    <groupId>org.apache.jena</groupId>
    <artifactId>jena-tdb2</artifactId>
</dependency>
<dependency>
    <groupId>org.apache.jena</groupId>
    <artifactId>jena-fuseki-server</artifactId>
</dependency>
<dependency>
    <groupId>org.apache.jena</groupId>
    <artifactId>jena-fuseki-core</artifactId>
</dependency>
```

---

## Named Graph Structure

### Graph Organization

Each ontology is stored in a **separate named graph** within the TDB2 dataset.

```
TDB2 Dataset: /ismd-tool-dataset
│
├── Named Graph: <https://data.dia.gov.cz/zdroj/slovníky/ontology-1>
│   ├── Ontology metadata triples (vocabulary info, labels, timestamps)
│   └── Concept triples (all classes, properties, relationships in this ontology)
│
├── Named Graph: <https://data.dia.gov.cz/zdroj/slovníky/ontology-2>
│   ├── Ontology metadata triples
│   └── Concept triples
│
└── Named Graph: <https://data.dia.gov.cz/zdroj/slovníky/ontology-n>
    └── ...
```

**Graph Naming Convention**:
- Graph IRI = Ontology IRI
- Example: `https://data.dia.gov.cz/zdroj/slovníky/vocabulary-name`
- Stored in PostgreSQL as `graph_name` column

**Linking Mechanism**:
- PostgreSQL `ontologies.graph_name` → Fuseki named graph URI
- PostgreSQL `concepts.graph_name` → Fuseki named graph URI (same as parent ontology)
- PostgreSQL `concepts.concept_iri` → Fuseki RDF resource URI

---

## RDF Vocabularies and Namespaces

### Standard W3C Vocabularies

| Prefix | Namespace | Purpose |
|--------|-----------|---------|
| `rdf` | `http://www.w3.org/1999/02/22-rdf-syntax-ns#` | RDF core vocabulary |
| `rdfs` | `http://www.w3.org/2000/01/rdf-schema#` | RDF Schema (subClassOf, domain, range, etc.) |
| `owl` | `http://www.w3.org/2002/07/owl#` | Web Ontology Language (ontologies, classes, properties) |
| `skos` | `http://www.w3.org/2004/02/skos/core#` | Simple Knowledge Organization System |
| `dct` / `dcterms` | `http://purl.org/dc/terms/` | Dublin Core metadata terms |
| `xsd` | `http://www.w3.org/2001/XMLSchema#` | XML Schema datatypes |

### OFN Vocabularies

| Prefix | Namespace | Purpose |
|--------|-----------|---------|
| `ofn` | `https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/` | OFN vocabulary terms (concept types, properties) |
| `ofn-legal` | `https://slovník.gov.cz/legislativní/sbírka/111/2009/pojem/` | Legal/legislative vocabulary |
| `a104` | `https://slovník.gov.cz/agendový/104/pojem/` | Agenda 104 governance vocabulary (PPDF, agenda codes) |
| `l111-2009` | `https://slovník.gov.cz/legislativní/sbírka/111/2009/pojem/` | Legislative collection 111/2009 |
| `cas` | `https://slovník.gov.cz/generický/čas/pojem/` | Temporal vocabulary (timestamps, moments) |
| `slovniky` | `https://slovník.gov.cz/generický/slovníky/pojem/` | Generic vocabulary vocabulary |

### Application-Specific Namespaces

| Prefix | Namespace | Purpose |
|--------|-----------|---------|
| `default` | `https://data.dia.gov.cz/zdroj/slovníky/` | Default namespace for ontologies |
| `schema` | `http://schema.org/` | Schema.org vocabulary (for URL, etc.) |

---

## Ontology RDF Structure

### Ontology Resource

An ontology is represented as an RDF resource with multiple types and metadata properties.

**RDF Types**:
```turtle
<https://data.dia.gov.cz/zdroj/slovníky/example-ontology>
    rdf:type owl:Ontology ;
    rdf:type skos:ConceptScheme ;
    rdf:type <https://slovník.gov.cz/generický/slovníky/pojem/slovník> .
```

### Ontology Type Classifications

**Type IRIs**:
- `owl:Ontology` - Standard OWL ontology
- `skos:ConceptScheme` - SKOS concept scheme (for taxonomies/thesauri)
- `https://slovník.gov.cz/generický/slovníky/pojem/slovník` - OFN vocabulary type

**JSON-LD Type Labels**:
- "Slovník" - Vocabulary
- "Tezaurus" - Thesaurus
- "Konceptuální model" - Conceptual model

---

## Concept RDF Structures

### 1. Class Concept (Třída)

A class represents an entity type or category in the domain model.

**RDF Types**:
```turtle
<concept-uri>
    rdf:type skos:Concept ;
    rdf:type ofn:pojem ;
    rdf:type ofn:třída ;
    rdf:type ofn:typ-subjektu-nebo-události ;  # TSP (Subject or Event Type)
    rdf:type ofn-legal:veřejný-údaj .          # Public data (optional)
```

### 2. Property Concept (Vlastnost - Datatype Property)

A property represents an attribute with a literal value (string, number, date, etc.).

**RDF Types**:
```turtle
<concept-uri>
    rdf:type skos:Concept ;
    rdf:type ofn:pojem ;
    rdf:type ofn:vlastnost ;
    rdf:type owl:DatatypeProperty .
```

**Common Datatype Ranges**:
- `xsd:string` - Text
- `xsd:integer` - Whole numbers
- `xsd:decimal` - Decimal numbers
- `xsd:boolean` - True/false
- `xsd:date` - Date (YYYY-MM-DD)
- `xsd:dateTime` - Date and time
- `xsd:anyURI` - URI/URL

---

### 3. Relationship Concept (Vztah - Object Property)

A relationship represents a connection between two entity types.

**RDF Types**:
```turtle
<concept-uri>
    rdf:type skos:Concept ;
    rdf:type ofn:pojem ;
    rdf:type ofn:vztah ;
    rdf:type owl:ObjectProperty .
```

## Concept Type Classifications

### OFN Concept Type Hierarchy

```
ofn:pojem (Base concept type - always present)
│
├── ofn:třída (Class)
│   ├── ofn:typ-subjektu-nebo-události (TSP - Subject or Event Type)
│   └── ofn:typ-objektu-nebo-vlastnosti (TOP - Object or Property Type)
│
├── ofn:vlastnost (Property / Datatype Property)
│
└── ofn:vztah (Relationship / Object Property)
```

### Legal Classification

```
<https://slovník.gov.cz/legislativní/sbírka/111/2009/pojem/>
│
├── veřejný-údaj (Public data)
└── neveřejný-údaj (Non-public data)
```

### Concept Type Mapping Table

| Czech Term | RDF Type | OWL Equivalent |
|-----|------|----------------|
| Pojem | `ofn:pojem` | `skos:Concept` |
| Třída | `ofn:třída` | `owl:Class` |
| Vlastnost | `ofn:vlastnost` | `owl:DatatypeProperty` |
| Vztah | `ofn:vztah` | `owl:ObjectProperty` |
| TSP | `ofn:typ-subjektu-nebo-události` | Subtype of Class |
| TOP | `ofn:typ-objektu-nebo-vlastnosti` | Subtype of Class |

---

## Multi-Language Support

All textual properties support multiple languages using **language tags**.

### Language Tag Format

```turtle
skos:prefLabel "Person"@en ;
skos:prefLabel "Osoba"@cs ;
skos:prefLabel "Personne"@fr ;

skos:definition "A natural or legal person"@en ;
skos:definition "Fyzická nebo právnická osoba"@cs ;
```

### Common Language Codes

- `@cs` - Czech (primary language)
- `@en` - English
- `@sk` - Slovak

**Default Language**: Czech (`cs`)

### Language-Tagged Properties

| Property | Purpose |
|----------|---------|
| `skos:prefLabel` | Preferred label/name |
| `skos:altLabel` | Alternative label/name |
| `skos:definition` | Formal definition |
| `dct:description` | Detailed description |

---

## Semantic Relationships and Hierarchies

### Class Hierarchies

**Superclass (Parent Class)**:
```turtle
<child-class>
    rdfs:subClassOf <parent-class> .
```

**Multiple Inheritance**:
```turtle
<child-class>
    rdfs:subClassOf <parent-class-1> ;
    rdfs:subClassOf <parent-class-2> .
```

### Property Hierarchies

**Super Property**:
```turtle
<child-property>
    rdfs:subPropertyOf <parent-property> .
```

### Relationship Hierarchies

**Super Relation**:
```turtle
<child-relation>
    rdfs:subPropertyOf <parent-relation> .
```

### Equivalent Concepts (External Mappings)

**Exact Match** (same meaning in external vocabulary):
```turtle
<concept-uri>
    skos:exactMatch <http://xmlns.com/foaf/0.1/Person> ;
    skos:exactMatch <http://schema.org/Person> .
```
---

## Repository Operations

### Primary Repository Class

**File**: `com.dia.ismdtoolbackend.repository.JenaTDB2Repository`

**Connection Method**:
```java
// HTTP connection to Fuseki endpoint
RDFConnection conn = RDFConnection.connect(fusekiUrl);
```

### CRUD Operations

#### Create/Save Concept

```java
String saveConcept(Resource conceptResource, String graphName)
```

**Process**:
1. Extract concept IRI from resource
2. Create model from resource
3. Load model into named graph
4. Return concept IRI

#### Read/Fetch Graph

```java
Model fetchGraph(String graphName)
```

**Process**:
1. Connect to Fuseki
2. Fetch all triples from named graph
3. Return Jena Model

#### Update Ontology

```java
void saveOntologyModel(String graphName, Model model)  // Append/merge
void putOntologyModel(String graphName, Model model)   // Replace
```

#### Delete Concept

```java
void deleteConcept(String conceptUri)
void deleteConceptFromGraph(String conceptUri, String graphName)
void deleteConceptsFromGraph(List<String> conceptUris, String graphName)
```

Deletes:
1. All triples where concept is the subject
2. All triples where concept is the object

#### Delete Graph

```java
void deleteGraph(String graphName)
```

### Query Operations

#### Check Concept Existence

```java
boolean conceptExists(String conceptUri)
boolean conceptExistsInGraph(String conceptUri, String graphName)
```

#### Find Graph Containing Concept

```java
String findGraphContainingConcept(String conceptUri)
```

---

## SPARQL Query Patterns

### Construct Query for Concept Details

**Purpose**: Retrieve complete concept data including nested blank nodes

**File**: `com.dia.ismdtoolbackend.query.NKDSPARQLConstructQuery`

```sparql
PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
PREFIX owl: <http://www.w3.org/2002/07/owl#>
PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
PREFIX dct: <http://purl.org/dc/terms/>
PREFIX ofn: <https://slovník.gov.cz/generický/datový-slovník-ofn-slovníků/pojem/>

CONSTRUCT {
  ?concept ?p ?o .
  ?o ?nestedP ?nestedO .
}
WHERE {
  BIND(<concept-uri> as ?concept)

  # Direct properties
  {
    ?concept ?p ?o .
  }
  UNION
  # Nested blank node properties
  {
    ?concept ?p ?o .
    FILTER(isBlank(?o))
    ?o ?nestedP ?nestedO .
  }
}
```

**Fetches**:
- All direct triples where concept is subject
- All nested triples for blank node objects (e.g., temporal moments, digital documents)

### ASK Query for Existence

```sparql
ASK {
  ?s ?p ?o
}
```

Returns: `true` if any triple exists, `false` otherwise

### SELECT Query for Graph Discovery

```sparql
SELECT ?g
WHERE {
  GRAPH ?g {
    <concept-uri> ?p ?o
  }
}
LIMIT 1
```

Returns: Graph URI containing the concept

---

## Data Flow Diagrams

### Write Flow: Create Concept

```
┌──────────────────┐
│ POST /api/concept│
│ /{slug}/create   │
└────────┬─────────┘
         │
         ▼
┌─────────────────────────┐
│  ConceptController      │
└────────┬────────────────┘
         │
         ▼
┌─────────────────────────┐
│  ConceptService         │
└────────┬────────────────┘
         │
         ▼
┌─────────────────────────┐
│  ConceptCreator         │
│  (creates RDF model)    │
└────────┬────────────────┘
         │
         ▼
┌─────────────────────────┐
│  OFNBaseModel           │
│  - Creates OntModel     │
│  - Adds RDF types       │
│  - Adds properties      │
│  - Multi-language       │
└────────┬────────────────┘
         │
         ▼
┌─────────────────────────┐
│  Resource (Jena)        │
│  with complete triples  │
└────────┬────────────────┘
         │
         ▼
┌─────────────────────────┐
│  JenaTDB2Repository     │
│  .saveConcept()         │
└────────┬────────────────┘
         │
         ▼
┌─────────────────────────┐
│  RDFConnection          │
│  .load(graph, model)    │
└────────┬────────────────┘
         │
         ▼
┌─────────────────────────┐
│  Apache Fuseki          │
│  (TDB2 storage)         │
└─────────────────────────┘
         │
         ▼ (parallel)
┌─────────────────────────┐
│  PostgreSQL             │
│  ConceptMetadataEntity  │
└─────────────────────────┘
```

### Read Flow: Get Ontology Detail

```
┌──────────────────┐
│ GET /api/ontology│
│ /{slug}/detail   │
└────────┬─────────┘
         │
         ▼
┌─────────────────────────┐
│  OntologyController     │
└────────┬────────────────┘
         │
         ▼
┌─────────────────────────┐
│  OntologyService        │
└────────┬────────────────┘
         │
         ▼
┌─────────────────────────┐
│  JenaTDB2Repository     │
│  .fetchGraph()          │
└────────┬────────────────┘
         │
         ▼
┌─────────────────────────┐
│  Apache Fuseki          │
│  SPARQL CONSTRUCT       │
└────────┬────────────────┘
         │
         ▼
┌─────────────────────────┐
│  Raw Jena Model         │
└────────┬────────────────┘
         │
         ▼
┌──────────────────────────────┐
│  OntologyDetailExtractor     │
│  .applyOFNTransformations()  │
└────────┬─────────────────────┘
         │
         ▼
┌─────────────────────────┐
│  TurtleFilterUtil       │
│  TurtleFormatterUtil    │
└────────┬────────────────┘
         │
         ▼
┌─────────────────────────┐
│  ModelAnalyzer          │
│  - Extract ontology meta│
│  - Identify concepts    │
└────────┬────────────────┘
         │
         ▼
┌─────────────────────────┐
│  ConceptProcessor       │
│  - Process each concept │
│  - Extract properties   │
│  - Build relationships  │
└────────┬────────────────┘
         │
         ▼
┌─────────────────────────┐
│  OntologyDetailModel    │
│  (JSON structure)       │
└─────────────────────────┘
```

### Export Flow: Download TTL/JSON-LD

```
┌──────────────────┐
│ GET /api/ontology│
│ /{id}/download   │
│ ?format=ttl      │
└────────┬─────────┘
         │
         ▼
┌─────────────────────────┐
│  OntologyController     │
└────────┬────────────────┘
         │
         ▼
┌─────────────────────────┐
│  OntologyDownloadService│
└────────┬────────────────┘
         │
         ▼
┌─────────────────────────┐
│  JenaTDB2Repository     │
│  .fetchGraph()          │
└────────┬────────────────┘
         │
         ▼
┌─────────────────────────┐
│  Jena Model             │
└────────┬────────────────┘
         │
         ├─────────────────────────┐
         │                         │
         ▼ (TTL)                   ▼ (JSON-LD)
┌──────────────────┐    ┌─────────────────────────┐
│ model.write()    │    │  JsonExporter           │
│ format="TTL"     │    │  - ModelAnalyzer        │
└──────────────────┘    │  - ConceptProcessor     │
                        │  - JsonFormatter        │
                        └─────────────────────────┘
         │                         │
         ▼                         ▼
┌──────────────────┐    ┌─────────────────────────┐
│ Turtle String    │    │  JSON-LD String         │
└──────────────────┘    └─────────────────────────┘
```

---

## JSON-LD Export Structure

### Context

**Context URL**:
```
https://data.dia.gov.cz/kontexty/popis-slovníků-popis-dat.jsonld
```

This context defines the mapping from Czech property names to RDF predicates.

---

## Utility Classes

### RDF Creation and Manipulation

| Utility Class | Purpose | Key Methods |
|---------------|---------|-------------|
| `ConceptCreator` | Creates RDF resources from domain models | `createClass()`, `createProperty()`, `createRelationship()` |
| `OntologyCreator` | Creates ontology RDF resources | `createOntology()` |
| `ConceptEditor` | Edits existing concept RDF | `editConcept()` |
| `OntologyEditor` | Edits existing ontology RDF | `editOntology()` |
| `URIGenerator` | Generates IRIs for concepts and vocabularies | `generateConceptIRI()`, `generateVocabularyIRI()` |

### RDF Processing and Export

| Utility Class | Purpose | Key Methods |
|---------------|---------|-------------|
| `OntologyDetailExtractor` | Extracts structured data from RDF | `applyOFNTransformations()`, `extract()` |
| `ModelAnalyzer` | Analyzes RDF model structure | `analyzeOntology()`, `extractConcepts()` |
| `ConceptProcessor` | Processes concepts for export | `processConcept()`, `extractProperties()` |
| `JsonFormatter` | Formats data as JSON-LD | `formatOntology()`, `formatConcept()` |
| `JsonExporter` | Exports RDF to JSON-LD | `export()` |
| `TurtleFilterUtil` | Filters RDF triples | `filterTriples()` |
| `TurtleFormatterUtil` | Transforms to OFN format | `format()` |

---

## Performance Considerations

### Indexing

Fuseki/TDB2 automatically creates indexes for efficient SPARQL queries:

- **SPO** - Subject-Predicate-Object
- **POS** - Predicate-Object-Subject
- **OSP** - Object-Subject-Predicate

These indexes enable fast lookups for different query patterns.

---

## Backup and Recovery

### TDB2 Backup

**Recommended Approach**:
```bash
# Online backup (Fuseki running)
curl -X POST http://localhost:3030/$/backup/ismd-tool-dataset

# Offline backup (Fuseki stopped)
cp -r /data/tdb2/production-dataset /backup/tdb2-$(date +%Y%m%d)
```

**Backup Frequency**: Daily

**Retention**: 30 days minimum

### Recovery

**From Online Backup**:
1. Stop Fuseki
2. Remove corrupted dataset
3. Restore from backup
4. Start Fuseki

**From Offline Backup**:
1. Stop Fuseki
2. Replace dataset directory
3. Start Fuseki

### Consistency Requirements

**Critical**: PostgreSQL and TDB2 backups must be synchronized to maintain referential integrity through `graph_name` and IRI references.

**Recommendation**: Backup PostgreSQL and TDB2 in sequence within the same backup window.

---

## Security Considerations

### Access Control

Fuseki endpoint security is managed at the **application layer**, not at the database level.

**Application Enforcement**:
- All Fuseki operations go through `JenaTDB2Repository`
- Controllers enforce authorization via `@PreAuthorize` annotations
- Services verify ownership before write operations

**No Direct Fuseki Access**: Production Fuseki should not be publicly accessible; only the application backend should connect.

### Data Validation

**RDF Validation**:
- External validation service validates SHACL/SKOS compliance
- Validation results stored in PostgreSQL `validation_reports` table
- Ontologies can be blocked from download if validation fails (configurable)

**Input Sanitization**:
- IRIs validated before RDF generation
- User input sanitized to prevent SPARQL injection

---

## Monitoring and Maintenance

### Fuseki Monitoring

**Endpoints**:
- Health: `http://localhost:3030/$/ping`
- Statistics: `http://localhost:3030/$/stats/{dataset}`
- Server info: `http://localhost:3030/$/server`

**Metrics to Monitor**:
- Query execution time
- Number of triples per graph
- HTTP request rate
- Memory usage

### TDB2 Maintenance

**Compaction** (reduces disk space):
```bash
# Requires Fuseki to be stopped
tdb2.tdbcompact --loc=/data/tdb2/production-dataset
```

**Frequency**: Monthly or when disk usage increases significantly

**Statistics Update** (improves query performance):
```bash
tdb2.tdbstats --loc=/data/tdb2/production-dataset --graph=<graph-uri>
```

---

## Appendix

### Key File Locations

| Component | File Path |
|-----------|-----------|
| Jena Configuration | `src/main/java/com/dia/ismdtoolbackend/config/JenaConfig.java` |
| TDB2 Repository | `src/main/java/com/dia/ismdtoolbackend/repository/JenaTDB2Repository.java` |
| SPARQL Construct Query | `src/main/java/com/dia/ismdtoolbackend/query/NKDSPARQLConstructQuery.java` |
| Concept Creator | `src/main/java/com/dia/ismdtoolbackend/utility/ConceptCreator.java` |
| Ontology Creator | `src/main/java/com/dia/ismdtoolbackend/utility/OntologyCreator.java` |
| Model Analyzer | `src/main/java/com/dia/ismdtoolbackend/utility/ModelAnalyzer.java` |
| JSON Exporter | `src/main/java/com/dia/ismdtoolbackend/utility/JsonExporter.java` |
| Concept Processor | `src/main/java/com/dia/ismdtoolbackend/utility/ConceptProcessor.java` |

### External Resources

| Resource | URL |
|----------|-----|
| Apache Jena Documentation | https://jena.apache.org/documentation/ |
| SPARQL 1.1 Specification | https://www.w3.org/TR/sparql11-query/ |
| SKOS Reference | https://www.w3.org/TR/skos-reference/ |
| OWL 2 Primer | https://www.w3.org/TR/owl2-primer/ |
| Czech OFN Standards | https://ofn.gov.cz/ |
| ELI (European Legislation Identifier) | https://eur-lex.europa.eu/eli-register/about.html |

---

**Document Version**: 1.0
**Last Updated**: 2025-12-31
**Author**: Richard Koubek (generated)
**Project**: ISMD Tool Backend