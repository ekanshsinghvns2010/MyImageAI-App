FROM python:3.12-slim
ENV PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1
WORKDIR /srv
COPY requirements.txt .
RUN pip install -r requirements.txt
COPY app ./app
RUN useradd -r -u 10002 inference
USER inference
EXPOSE 8001
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://localhost:8001/v1/health', timeout=3).status == 200 else 1)"
# For GPU inference, base this image on an NVIDIA CUDA/PyTorch image instead and run the container with the NVIDIA runtime.
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8001"]
