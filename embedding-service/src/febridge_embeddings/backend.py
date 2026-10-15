from typing import Protocol

from .settings import Settings


class Backend(Protocol):
    dimension: int
    device: str
    max_tokens: int

    def load(self) -> None: ...
    def token_lengths(self, texts: list[str]) -> list[int]: ...
    def encode(self, texts: list[str]) -> list[list[float]]: ...
    def close(self) -> None: ...


class SentenceTransformerBackend:
    def __init__(self, settings: Settings):
        self.settings = settings
        self.model = None
        self.dimension = 0
        self.device = settings.device
        self.max_tokens = settings.max_input_tokens

    def load(self) -> None:
        # Keep inference dependencies optional for interface tests.
        try:
            import torch
            from sentence_transformers import SentenceTransformer
        except ImportError as error:
            raise RuntimeError('请先安装模型依赖：pip install -e ".[model]"') from error

        if self.device == "auto":
            self.device = "cuda" if torch.cuda.is_available() else "cpu"
        self.model = SentenceTransformer(
            self.settings.model_source,
            device=self.device,
            cache_folder=self.settings.cache_dir,
            revision=self.settings.model_revision,
            trust_remote_code=False,
        )
        self.max_tokens = min(self.settings.max_input_tokens, self.model.max_seq_length)
        self.model.max_seq_length = self.max_tokens
        self.dimension = self.model.get_sentence_embedding_dimension()
        if not isinstance(self.dimension, int) or self.dimension <= 0:
            raise RuntimeError("模型未提供有效的向量维度")

    def token_lengths(self, texts: list[str]) -> list[int]:
        encoded = self.model.tokenizer(
            texts, add_special_tokens=True, padding=False, truncation=False,
        )
        return [len(ids) for ids in encoded["input_ids"]]

    def encode(self, texts: list[str]) -> list[list[float]]:
        import torch

        with torch.inference_mode():
            values = self.model.encode(
                texts,
                batch_size=self.settings.batch_size,
                normalize_embeddings=True,
                convert_to_numpy=True,
                show_progress_bar=False,
            )
        return values.tolist()

    def close(self) -> None:
        self.model = None
