import base64
import struct
from dataclasses import replace

import pytest
from fastapi.testclient import TestClient

from febridge_embeddings.app import create_app
from febridge_embeddings.settings import Settings


class FakeBackend:
    dimension = 3
    device = "cpu"
    max_tokens = 512

    def load(self):
        self.loaded = True

    def close(self):
        self.loaded = False

    def token_lengths(self, texts):
        return [len(text) + 2 for text in texts]

    def encode(self, texts):
        return [[1.0, 0.0, 0.0] for text in texts]


def client(settings=None, backend=None):
    backend = backend or FakeBackend()
    return TestClient(create_app(settings or Settings(), lambda settings: backend))


def test_single_batch_usage_and_model_metadata():
    with client() as api:
        result = api.post("/v1/embeddings", json={"model": "bge-m3", "input": ["模具", "转移"]})
        assert result.status_code == 200
        assert result.json()["usage"]["total_tokens"] == 8
        assert [item["index"] for item in result.json()["data"]] == [0, 1]
        assert result.json()["data"][0]["embedding"] == [1.0, 0.0, 0.0]
        assert api.get("/ready").json()["dimensions"] == 3
        assert api.get("/v1/models").json()["data"][0]["id"] == "bge-m3"
        assert api.post("/v1/embeddings", json={"model": "bge-m3", "input": "模具"}).status_code == 200


@pytest.mark.parametrize("payload", [
    {"model": "bge-m3", "input": []},
    {"model": "bge-m3", "input": "  "},
    {"model": "bge-m3", "input": [12]},
    {"model": "bge-m3", "input": "text", "dimensions": 2},
    {"model": "bge-m3", "input": "text", "encoding_format": "int8"},
    {"model": "bge-m3", "input": "text", "unexpected": "secret"},
])
def test_invalid_requests_have_compatible_error_without_input_echo(payload):
    with client() as api:
        response = api.post("/v1/embeddings", json=payload)
        assert response.status_code == 400
        assert "error" in response.json()
        assert "secret" not in response.text


def test_base64_float32_and_unknown_model():
    with client() as api:
        response = api.post("/v1/embeddings", json={
            "model": "bge-m3", "input": "query", "encoding_format": "base64", "dimensions": 3,
        })
        raw = base64.b64decode(response.json()["data"][0]["embedding"])
        assert struct.unpack("<fff", raw) == (1.0, 0.0, 0.0)
        response = api.post("/v1/embeddings", json={"model": "other", "input": "query"})
        assert response.status_code == 404


def test_api_key_and_health():
    with client(Settings(api_key="unit-test-key")) as api:
        assert api.get("/health").status_code == 200
        assert api.get("/v1/models").status_code == 401
        response = api.post("/v1/embeddings", json={"model": "bge-m3", "input": "query"},
            headers={"Authorization": "Bearer unit-test-key"})
        assert response.status_code == 200


def test_input_and_body_limits():
    settings = replace(Settings(), max_inputs=1, max_input_chars=8, max_total_chars=8, max_body_bytes=200)
    with client(settings) as api:
        for texts in (["one", "two"], "x" * 9):
            assert api.post("/v1/embeddings", json={"model": "bge-m3", "input": texts}).status_code == 400
        assert api.post("/v1/embeddings", content="x" * 201).status_code == 413
    backend = FakeBackend()
    backend.max_tokens = 4
    with client(backend=backend) as api:
        assert api.post("/v1/embeddings", json={"model": "bge-m3", "input": "long"}).status_code == 400


def test_busy_response_and_failure_release_lock():
    backend = FakeBackend()
    with client(backend=backend) as api:
        service = api.app.state.embedding_service
        service.lock.acquire()
        try:
            response = api.post("/v1/embeddings", json={"model": "bge-m3", "input": "query"})
            assert response.status_code == 429
            assert response.headers["Retry-After"] == "1"
        finally:
            service.lock.release()
        backend.encode = lambda texts: [[float("nan"), 0.0, 0.0] for text in texts]
        assert api.post("/v1/embeddings", json={"model": "bge-m3", "input": "query"}).status_code == 500
        backend.encode = lambda texts: [[1.0, 0.0, 0.0] for text in texts]
        assert api.post("/v1/embeddings", json={"model": "bge-m3", "input": "query"}).status_code == 200
