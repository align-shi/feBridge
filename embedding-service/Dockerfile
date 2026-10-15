FROM python:3.12-slim
WORKDIR /app
COPY pyproject.toml ./
COPY src ./src
RUN pip install --no-cache-dir "torch>=2.6,<3" --index-url https://download.pytorch.org/whl/cpu \
    && pip install --no-cache-dir ".[model]"
ENV EMBEDDING_HOST=0.0.0.0 EMBEDDING_PORT=8091 EMBEDDING_DEVICE=cpu
EXPOSE 8091
CMD ["python", "-m", "febridge_embeddings"]
