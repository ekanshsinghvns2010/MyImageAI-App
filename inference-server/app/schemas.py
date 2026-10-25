from pydantic import BaseModel, ConfigDict, Field


class GenerateRequest(BaseModel):
    # "model_id" would otherwise trigger pydantic's protected "model_" namespace warning.
    model_config = ConfigDict(protected_namespaces=())

    model_id: str
    prompt: str = Field(min_length=1, max_length=4000)
    negative_prompt: str | None = Field(default=None, max_length=4000)
    width: int = Field(ge=64, le=4096)
    height: int = Field(ge=64, le=4096)
    steps: int = Field(ge=1, le=1000)
    seed: int | None = Field(default=None, ge=0)
    num_images: int = Field(default=1, ge=1, le=64)


class ImageOut(BaseModel):
    mime_type: str
    data_base64: str
    width: int
    height: int
    seed: int | None = None


class GenerateResponse(BaseModel):
    model_config = ConfigDict(protected_namespaces=())

    model_id: str
    simulated: bool
    images: list[ImageOut]
