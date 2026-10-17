#!/usr/bin/env bash
set -u
python /app/patch_settings.py

echo "[start.sh] Starting Rafiq Precision Quranic Auditor Server v4 on port 8001..."
exec python -m uvicorn app_server:app --host 0.0.0.0 --port 8001
