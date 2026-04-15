#!/bin/bash
# Loads .env and starts the full-backend profile (includes Spring Boot backend)
set -a
source .env
set +a

docker-compose --profile full-backend up -d "$@"
