FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends tesseract-ocr curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /srv
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
# Face detection / recognition models for the selfie face match (checksum-verified)
COPY scripts/fetch_models.sh ./scripts/
RUN sh scripts/fetch_models.sh /srv/data/models
COPY app ./app
# CSCA certificates / master lists used for Passive Authentication
COPY data/csca ./data/csca
# Committed TEST issuer PKI (see data/pki/README.md). For real keys, point PID_PKI_DIR at
# a secrets mount (Render: /etc/secrets) or a persistent disk instead.
COPY data/pki ./data/pki
ENV PID_PKI_DIR=/srv/data/pki PID_CSCA_DIR=/srv/data/csca PID_MODEL_DIR=/srv/data/models PORT=8000
EXPOSE 8000
# Single worker: issuance sessions (AA/CA challenges) are kept in memory
CMD ["sh", "-c", "exec uvicorn app.main:app --host 0.0.0.0 --port ${PORT} --proxy-headers --forwarded-allow-ips='*'"]
