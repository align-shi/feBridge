import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    model_source: str = "BAAI/bge-m3"
    model_name: str = "bge-m3"
    model_revision: str | None = None
    device: str = "auto"
    cache_dir: str = ".cache/models"
    api_key: str = ""
    batch_size: int = 8
    max_inputs: int = 32
    max_input_tokens: int = 512
    max_input_chars: int = 32768
    max_total_chars: int = 131072
    max_body_bytes: int = 1048576

    def __post_init__(self):
        if not self.model_source.strip() or not self.model_name.strip():
            raise ValueError("模型来源和服务模型名称不能为空")
        for field in (
            "batch_size", "max_inputs", "max_input_tokens", "max_input_chars",
            "max_total_chars", "max_body_bytes",
        ):
            if getattr(self, field) <= 0:
                raise ValueError(f"{field} 必须大于 0")
        if self.device not in ("auto", "cpu", "cuda") and not (
            self.device.startswith("cuda:") and self.device[5:].isdigit()
        ):
            raise ValueError("device 必须为 auto、cpu、cuda 或 cuda:N")

    @classmethod
    def from_env(cls):
        values = {}
        for name in cls.__dataclass_fields__:
            raw = os.getenv("EMBEDDING_" + name.upper())
            if raw is not None:
                if name == "model_revision":
                    values[name] = raw.strip() or None
                elif name in (
                    "batch_size", "max_inputs", "max_input_tokens", "max_input_chars",
                    "max_total_chars", "max_body_bytes",
                ):
                    values[name] = int(raw)
                else:
                    values[name] = raw
        return cls(**values)
