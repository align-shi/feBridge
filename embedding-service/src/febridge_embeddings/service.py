import base64
import math
import struct
import threading
from dataclasses import dataclass

from .backend import Backend
from .settings import Settings


@dataclass
class ServiceError(Exception):
    message: str
    status: int = 400
    param: str | None = None
    code: str = "invalid_request_error"


class EmbeddingService:
    def __init__(self, settings: Settings, backend: Backend):
        self.settings = settings
        self.backend = backend
        self.ready = False
        self.lock = threading.Lock()

    def start(self) -> None:
        self.backend.load()
        self.ready = True

    def close(self) -> None:
        self.ready = False
        self.backend.close()

    def generate(self, model: str, inputs: str | list[str], encoding: str, dimensions: int | None):
        if not self.ready:
            raise ServiceError("模型尚未就绪", 503, code="model_not_ready")
        if model != self.settings.model_name:
            raise ServiceError("模型名称不存在，请通过 /v1/models 查询", 404, "model", "model_not_found")
        texts = [inputs] if isinstance(inputs, str) else inputs
        if not texts or len(texts) > self.settings.max_inputs:
            raise ServiceError(f"input 需要包含 1–{self.settings.max_inputs} 条文本", param="input")
        if any(not text.strip() for text in texts):
            raise ServiceError("input 不能包含空白文本", param="input")
        if any(len(text) > self.settings.max_input_chars for text in texts):
            raise ServiceError("单条文本字符数超过限制", param="input")
        if sum(map(len, texts)) > self.settings.max_total_chars:
            raise ServiceError("批量文本总字符数超过限制", param="input")
        if dimensions is not None and dimensions != self.backend.dimension:
            raise ServiceError(
                f"当前模型输出固定为 {self.backend.dimension} 维，不支持任意截断",
                param="dimensions",
            )
        if not self.lock.acquire(blocking=False):
            raise ServiceError("模型正在处理其他请求，请稍后重试", 429, code="model_busy")
        try:
            lengths = self.backend.token_lengths(texts)
            if len(lengths) != len(texts):
                raise RuntimeError("分词结果数量不正确")
            if any(length > self.backend.max_tokens for length in lengths):
                raise ServiceError(
                    f"单条文本超过 {self.backend.max_tokens} Token，请先分段",
                    param="input",
                )
            vectors = self.backend.encode(texts)
            if len(vectors) != len(texts):
                raise RuntimeError("向量数量不正确")
            data = []
            for index, vector in enumerate(vectors):
                if len(vector) != self.backend.dimension or not all(math.isfinite(value) for value in vector):
                    raise RuntimeError("模型返回的向量无效")
                embedding = vector
                if encoding == "base64":
                    embedding = base64.b64encode(
                        struct.pack("<" + "f" * len(vector), *vector)
                    ).decode("ascii")
                data.append({"object": "embedding", "index": index, "embedding": embedding})
            count = sum(lengths)
            return {
                "object": "list",
                "data": data,
                "model": self.settings.model_name,
                "usage": {"prompt_tokens": count, "total_tokens": count},
            }
        finally:
            self.lock.release()
