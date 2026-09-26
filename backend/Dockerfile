FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends tesseract-ocr libgl1 libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /srv
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app ./app
# CSCA certificates / master lists; mount a volume here in production
COPY data/csca ./data/csca
ENV PID_PKI_DIR=/srv/data/pki PID_CSCA_DIR=/srv/data/csca
VOLUME ["/srv/data/pki"]
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
