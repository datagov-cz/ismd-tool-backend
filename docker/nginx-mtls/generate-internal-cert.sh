#!/bin/bash
# Generate self-signed certificate for nginx internal TLS
# Run once before first docker-compose up

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CERT_DIR="${SCRIPT_DIR}/certs"

mkdir -p "$CERT_DIR"

echo "Generating self-signed certificate for nginx-mtls..."

# Use MSYS_NO_PATHCONV only for the openssl command to prevent Git Bash path conversion
MSYS_NO_PATHCONV=1 openssl req -x509 -nodes -days 3650 -newkey rsa:2048 \
    -keyout "$CERT_DIR/internal.key" \
    -out "$CERT_DIR/internal.crt" \
    -subj "/CN=nginx-mtls/O=ISMD/C=CZ" \
    -addext "subjectAltName=DNS:nginx-mtls,DNS:localhost"

if [ $? -eq 0 ]; then
    echo "Internal certificate generated successfully at $CERT_DIR/"
    echo "Files created:"
    echo "  - $CERT_DIR/internal.crt"
    echo "  - $CERT_DIR/internal.key"
else
    echo "Failed to generate certificate"
    exit 1
fi
