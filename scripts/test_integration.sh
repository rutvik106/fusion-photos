#!/usr/bin/env bash
# Run the API's DB + S3 integration tests inside the compose network.
#
# The tests need Postgres + MinIO, which are only reachable as `db` / `minio` from
# inside the compose network (a host Postgres may otherwise shadow 5432). This runs
# the test binary in a throwaway Go container on that network.
#
#   ./scripts/test_integration.sh
#
# Requires the stack to be up: docker compose up -d db minio
set -euo pipefail

cd "$(dirname "$0")/.."

docker run --rm \
  --network fusion-photos_default \
  -v "$PWD/api:/src" \
  -v "$HOME/go/pkg/mod:/go/pkg/mod" \
  -w /src \
  -e FUSION_INTEGRATION=1 \
  -e DATABASE_URL='postgres://fusion:fusion@db:5432/fusion?sslmode=disable' \
  -e S3_ENDPOINT=minio:9000 \
  -e S3_ACCESS_KEY=fusion-dev \
  -e S3_SECRET_KEY=fusion-dev-secret \
  -e S3_REPLICA_BUCKET=fusion-media-replica \
  golang:1.27-alpine go test -run Integration -v ./cmd/server