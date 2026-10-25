"""The contract between the FastAPI server and YOUR model.

Implement `ModelAdapter` for your architecture. The server never guesses how to load a checkpoint:
a .pt / .safetensors file is just weights, and only you know the architecture that goes with it.
"""
from abc import ABC, abstractmethod
from dataclasses import dataclass

from PIL import Image

from app.schemas import GenerateRequest


@dataclass
class GeneratedImage:
    image: Image.Image
    seed: int | None = None


class ModelAdapter(ABC):
    #: True only for adapters that do NOT run a real model. Reported to the backend and API clients.
    simulated: bool = False
    name: str = "unnamed"

    def __init__(self) -> None:
        self._loaded = False

    @property
    def loaded(self) -> bool:
        return self._loaded

    @abstractmethod
    def load(self, model_path: str | None, model_id: str) -> None:
        """Load weights into memory (GPU if applicable). Raise on failure.
        Must set `self._loaded = True` only when the model is really ready."""

    @abstractmethod
    def generate(self, req: GenerateRequest) -> list[GeneratedImage]:
        """Return exactly `req.num_images` images of size (req.width, req.height).
        Called from a worker thread, one call at a time by default (see INFERENCE_MAX_CONCURRENCY)."""
