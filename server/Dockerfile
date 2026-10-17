FROM python:3.11-slim
ENV PYTHONUNBUFFERED=1 \
    HF_HOME=/tmp/hf \
    HUGGINGFACE_HUB_CACHE=/tmp/hf \
    ACCELERATOR=cpu DTYPE=float32 MAX_AUDIO_SECONDS=60 MAX_BATCH_SIZE=4 TIMEOUT=170
RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg libsndfile1 \
 && rm -rf /var/lib/apt/lists/*
RUN pip install --no-cache-dir torch --index-url https://download.pytorch.org/whl/cpu
RUN pip install --no-cache-dir "quran-muaalem[engine]"
WORKDIR /app
COPY patch_settings.py start.sh /app/
RUN chmod +x /app/start.sh
EXPOSE 8001
CMD ["/app/start.sh"]
