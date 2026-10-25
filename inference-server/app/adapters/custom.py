"""TEMPLATE for your trained MyImageAI model.  Select it with INFERENCE_ADAPTER=custom.

Nothing in here pretends to know your architecture. Fill in `load` and `generate`.
Until you do, the server starts, reports `model_loaded: false`, and /v1/generate answers 503.

Sketch for a Diffusers-style model (illustrative only, not executed):

    import torch
    from diffusers import StableDiffusionPipeline          # or your own pipeline class

    def load(self, model_path, model_id):
        self.device = "cuda" if torch.cuda.is_available() else "cpu"
        self.pipe = StableDiffusionPipeline.from_pretrained(model_path, torch_dtype=torch.float16).to(self.device)
        self._loaded = True

    def generate(self, req):
        gen = torch.Generator(self.device)
        if req.seed is not None:
            gen.manual_seed(req.seed)
        out = self.pipe(req.prompt, negative_prompt=req.negative_prompt, width=req.width, height=req.height,
                        num_inference_steps=req.steps, num_images_per_prompt=req.num_images, generator=gen)
        return [GeneratedImage(im, req.seed) for im in out.images]

For a plain PyTorch checkpoint, you must construct your own nn.Module, then
`model.load_state_dict(torch.load(model_path, map_location=device))`, and write the sampling loop.
"""
from app.adapters.base import GeneratedImage, ModelAdapter
from app.schemas import GenerateRequest


class MyImageAIAdapter(ModelAdapter):
    name = "custom"

    def load(self, model_path: str | None, model_id: str) -> None:
        raise NotImplementedError(
            "Implement MyImageAIAdapter.load() in app/adapters/custom.py for your model architecture "
            "(see the module docstring)."
        )

    def generate(self, req: GenerateRequest) -> list[GeneratedImage]:
        raise NotImplementedError("Implement MyImageAIAdapter.generate() in app/adapters/custom.py.")
