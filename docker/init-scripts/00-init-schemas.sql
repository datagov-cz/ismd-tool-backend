-- Create the application schema so Liquibase can bootstrap
-- (Liquibase needs the schema to exist before it can create its tracking tables)
CREATE SCHEMA IF NOT EXISTS ismd_schema;