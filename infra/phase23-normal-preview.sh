#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
preview_project=${PHASE23_NORMAL_PROJECT:-sicost-phase23-normal}
preview_frontend_port=${PHASE23_NORMAL_FRONTEND_PORT:-33123}
preview_backend_port=${PHASE23_NORMAL_BACKEND_PORT:-33124}

if ! [[ "$preview_project" =~ ^[a-z][a-z0-9-]*$ ]]; then
  echo "PHASE23_NORMAL_PROJECT must be a lowercase Compose project name" >&2
  exit 1
fi
if docker volume inspect "${preview_project}_postgres-data" >/dev/null 2>&1; then
  echo "The isolated project already has a database volume; refusing to change existing data" >&2
  exit 1
fi

export FRONTEND_PORT=$preview_frontend_port
export BACKEND_PORT=$preview_backend_port
compose=(docker compose -p "$preview_project" -f "$repo_root/infra/docker-compose.yml" -f "$repo_root/infra/phase23-normal-compose.yml")
"${compose[@]}" up -d --build
"${compose[@]}" exec -T db psql -v ON_ERROR_STOP=1 -U "${POSTGRES_USER:-sicost}" -d "${POSTGRES_DB:-sicost}" \
  < "$repo_root/infra/fixtures/phase23-normal.sql"
python3 "$repo_root/infra/phase23-normal-seed.py" "http://127.0.0.1:${preview_frontend_port}"
echo "Normal preview: http://127.0.0.1:${preview_frontend_port}/UI-14"
