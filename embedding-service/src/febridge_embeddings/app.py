import hmac
import logging
import time
from contextlib import asynccontextmanager
from typing import Callable, Literal

from fastapi import Depends, FastAPI, Header
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, StrictStr
from starlette.concurrency import run_in_threadpool

from .backend import Backend, SentenceTransformerBackend
from .service import EmbeddingService, ServiceError
from .settings import Settings

logger = logging.getLogger(__name__)


class EmbeddingRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    model: StrictStr = Field(min_length=1, max_length=200)
    input: StrictStr | list[StrictStr]
    encoding_format: Literal["float", "base64"] = "float"
    dimensions: int | None = Field(default=None, gt=0, strict=True)
    user: StrictStr | None = Field(default=None, max_length=128)


def error_response(error: ServiceError):
    headers = {"Retry-After": "1"} if error.status == 429 else None
    return JSONResponse(
        status_code=error.status,
        headers=headers,
        content={"error": {
            "message": error.message, "type": error.code,
            "param": error.param, "code": error.code,
        }},
    )


class BodyLimitMiddleware:
    def __init__(self, app, max_bytes: int):
        self.app = app
        self.max_bytes = max_bytes

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        chunks = []
        size = 0
        while True:
            message = await receive()
            if message["type"] == "http.disconnect":
                return
            chunk = message.get("body", b"")
            size += len(chunk)
            if size > self.max_bytes:
                response = error_response(ServiceError("请求体超过大小限制", 413, code="request_too_large"))
                await response(scope, receive, send)
                return
            chunks.append(chunk)
            if not message.get("more_body", False):
                break
        consumed = False

        async def replay():
            nonlocal consumed
            if not consumed:
                consumed = True
                return {"type": "http.request", "body": b"".join(chunks), "more_body": False}
            return await receive()

        await self.app(scope, replay, send)


def create_app(
    settings: Settings | None = None,
    backend_factory: Callable[[Settings], Backend] | None = None,
) -> FastAPI:
    settings = settings or Settings.from_env()
    factory = backend_factory or SentenceTransformerBackend
    service = EmbeddingService(settings, factory(settings))

    @asynccontextmanager
    async def lifespan(app):
        await run_in_threadpool(service.start)
        app.state.embedding_service = service
        try:
            yield
        finally:
            await run_in_threadpool(service.close)

    app = FastAPI(title="feBridge Embeddings", version="0.1.0", lifespan=lifespan)
    app.add_middleware(BodyLimitMiddleware, max_bytes=settings.max_body_bytes)

    @app.exception_handler(ServiceError)
    async def handle_service_error(request, error):
        return error_response(error)

    @app.exception_handler(RequestValidationError)
    async def handle_validation_error(request, error):
        # Do not return request text or credentials in validation details.
        return error_response(ServiceError("请求字段类型或格式不正确"))

    def authenticate(authorization: str | None = Header(default=None)):
        if not settings.api_key:
            return
        expected = "Bearer " + settings.api_key
        if authorization is None or not hmac.compare_digest(authorization, expected):
            raise ServiceError("API Key 不正确或未提供", 401, code="invalid_api_key")

    @app.get("/health")
    def health():
        return {"status": "ok"}

    @app.get("/ready")
    def ready():
        if not service.ready:
            raise ServiceError("模型尚未就绪", 503, code="model_not_ready")
        return {
            "status": "ready", "model": settings.model_name,
            "dimensions": service.backend.dimension, "device": service.backend.device,
            "max_input_tokens": service.backend.max_tokens,
        }

    @app.get("/v1/models", dependencies=[Depends(authenticate)])
    def models():
        return {"object": "list", "data": [{
            "id": settings.model_name, "object": "model", "owned_by": "febridge",
            "dimensions": service.backend.dimension,
        }]}

    @app.post("/v1/embeddings", dependencies=[Depends(authenticate)])
    def embeddings(request: EmbeddingRequest):
        started = time.perf_counter()
        try:
            result = service.generate(
                request.model, request.input, request.encoding_format, request.dimensions,
            )
        except ServiceError:
            raise
        except Exception:
            logger.exception("Embedding inference failed")
            return error_response(ServiceError("向量生成失败，请检查服务日志", 500, code="inference_error"))
        return JSONResponse(result, headers={
            "X-Embedding-Duration-Ms": str(round((time.perf_counter() - started) * 1000)),
        })

    return app
