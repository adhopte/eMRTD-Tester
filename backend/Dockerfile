FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends tesseract-ocr \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /srv
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app ./app
# CSCA certificates / master lists used for Passive Authentication
COPY data/csca ./data/csca
# PKI: point PID_PKI_DIR at a persistent disk or a secrets mount (Render: /etc/secrets)
ENV PID_PKI_DIR=/srv/data/pki PID_CSCA_DIR=/srv/data/csca PORT=8000
EXPOSE 8000
# Single worker: issuance sessions (AA/CA challenges) are kept in memory
CMD ["sh", "-c", "exec uvicorn app.main:app --host 0.0.0.0 --port ${PORT} --proxy-headers --forwarded-allow-ips='*'"]
