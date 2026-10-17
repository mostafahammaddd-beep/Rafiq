#!/usr/bin/env bash
set -u
python /app/patch_settings.py

quran-muaalem-engine &

# انتظر جاهزية المحرك (أول تشغيل يحمّل النموذج ≈ 2.4GB فقد يطول)
for i in $(seq 1 400); do
  python - <<'PY' && break
import sys, urllib.request
try:
    urllib.request.urlopen("http://127.0.0.1:8000/health", timeout=2)
    sys.exit(0)
except Exception:
    sys.exit(1)
PY
  sleep 3
done

exec quran-muaalem-app
