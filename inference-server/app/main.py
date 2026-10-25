import base64
import io
import logging
import os
import secrets
import threading
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, Header, HTTPException

from app.adapters.base import ModelAdapter
from app.schemas import GenerateRequest, GenerateResponse, ImageOut

log = logging.getLogger("inference")
logging.basicConfig(level=os.getenv("LOG_LEVEL", "INFO"))


def _select_adapter() -> ModelAdapter:
    choice = os.getenv("INFERENCE_ADAPTER", "mock").strip().lower()
    if choice == "mock":
        from app.adapters.mock import MockAdapter
        return MockAdapter()
    if choice == "custom":
        from app.adapters.custom import MyImageAIAdapter
        return MyImageAIAdapter()
    raise RuntimeError(f"Unknown INFERENCE_ADAPTER '{choice}' (expected 'mock' or 'custom')")


def create_app(
    adapter: ModelAdapter | None = None,
    token: str | None = None,
    model_id: str | None = None,
    model_path: str | None = None,
) -> FastAPI:
    adapter = adapter or _select_adapter()
    token = token if token is not None else (os.getenv("INFERENCE_API_TOKEN") or None)
    model_id = model_id or os.getenv("AI_MODEL_ID", "myimageai-v1")
    model_path = model_path if model_path is not None else os.getenv("AI_MODEL_PATH")
    # One inference at a time by default: GPUs are usually the bottleneck and OOM under parallel load.
    gate = threading.BoundedSemaphore(int(os.getenv("INFERENCE_MAX_CONCURRENCY", "1")))

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        try:
            if model_path and not adapter.simulated and not os.path.exists(model_path):
                raise FileNotFoundError(f"AI_MODEL_PATH does not exist inside the container: {model_path}")
            adapter.load(model_path, model_id)
            log.info("Adapter '%s' loaded (simulated=%s)", adapter.name, adapter.simulated)
        except Exception as e:  # keep serving /v1/health so operators can see what is wrong
            log.error("Model failed to load: %s: %s", type(e).__name__, e)
        if token is None:
            log.warning("INFERENCE_API_TOKEN is not set: the inference server accepts unauthenticated requests.")
        yield

    app = FastAPI(title="MyImageAI inference server", version="1.0.0", lifespan=lifespan)

    def require_token(authorization: str | None = Header(default=None)) -> None:
        if token is None:
            return
        supplied = authorization[7:] if authorization and authorization.lower().startswith("bearer ") else ""
        if not secrets.compare_digest(supplied.encode(), token.encode()):
            raise HTTPException(status_code=401, detail="invalid or missing bearer token")

    @app.get("/v1/health")
    def health():
        return {
            "status": "ok",
            "model_loaded": adapter.loaded,
            "model_id": model_id,
            "adapter": adapter.name,
            "simulated": adapter.simulated,
        }

    @app.post("/v1/generate", response_model=GenerateResponse, dependencies=[Depends(require_token)])
    def generate(req: GenerateRequest):
        if not adapter.loaded:
            raise HTTPException(status_code=503, detail="model is not loaded")
        if req.model_id != model_id:
            raise HTTPException(status_code=422, detail=f"unknown model_id '{req.model_id}'")
        with gate:
            try:
                results = adapter.generate(req)
            except Exception as e:
                log.exception("generation failed")
                raise HTTPException(status_code=500, detail="generation failed") from e
        if not results or len(results) > req.num_images:
            raise HTTPException(status_code=500, detail="adapter returned an unexpected number of images")
        images = []
        for r in results:
            buf = io.BytesIO()
            r.image.convert("RGB").save(buf, format="PNG")
            images.append(ImageOut(
                mime_type="image/png",
                data_base64=base64.b64encode(buf.getvalue()).decode("ascii"),
                width=r.image.width,
                height=r.image.height,
                seed=r.seed,
            ))
        return GenerateResponse(model_id=model_id, simulated=adapter.simulated, images=images)

    return app


app = create_app()
