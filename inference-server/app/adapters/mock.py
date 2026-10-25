from PIL import Image, ImageDraw

from app.adapters.base import GeneratedImage, ModelAdapter
from app.schemas import GenerateRequest


class MockAdapter(ModelAdapter):
    """Draws striped placeholders labelled SIMULATED. It runs NO model and must never be mistaken for one."""

    simulated = True
    name = "mock"

    def load(self, model_path: str | None, model_id: str) -> None:
        self._loaded = True

    def generate(self, req: GenerateRequest) -> list[GeneratedImage]:
        out = []
        for i in range(req.num_images):
            seed = (req.seed + i) if req.seed is not None else i
            img = Image.new("RGB", (req.width, req.height), (40, 40, 48))
            d = ImageDraw.Draw(img)
            for x in range(-req.height, req.width, 48):
                d.line([(x, 0), (x + req.height, req.height)], fill=(200, 150, 30), width=2)
            d.text((12, req.height // 2), "SIMULATED - NOT AI GENERATED", fill=(255, 255, 255))
            d.text((12, req.height // 2 + 16), f"mock adapter, seed {seed}", fill=(255, 255, 255))
            out.append(GeneratedImage(img, seed))
        return out
