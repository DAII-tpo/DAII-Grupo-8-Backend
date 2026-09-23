# Servicio de recomendación de estaciones (MOV-041). El modelo se entrena en el build, así el contenedor
# arranca con un modelo vigente y reproducible (semilla fija).
FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    MODEL_DIR=/app/models

WORKDIR /app

COPY requirements.lock .
RUN pip install --no-cache-dir --require-hashes --only-binary :all: -r requirements.lock

COPY app ./app
COPY training ./training
RUN python -m training.train \
    && useradd --create-home --uid 10001 appuser \
    && chown -R appuser /app

USER appuser
EXPOSE 8000

# Render inyecta PORT; en local se usa 8000.
CMD ["sh", "-c", "uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8000}"]
