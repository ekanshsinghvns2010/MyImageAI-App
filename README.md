# MyImageAI API

A Kotlin/Spring Boot backend that exposes your own text-to-image model as a secure, versioned REST API
(`/api/v1`), plus a separate Python/FastAPI inference-server template where your trained model plugs in.

> **Verification status (read this first).** This code was written in an environment with **no network access,
> no Gradle and no Python packages**, so **the Kotlin backend has not been compiled and its tests have not
> been run**, and the Python server tests have not been run (the Python files only passed a syntax check).
> Expect to fix a few compile errors on first build. See [What is verified / what is not](#what-is-verified--what-is-not).

## Architecture

```
 Android app / website / bot ──► your backend (BFF)* ──HTTPS + API key──► Kotlin API (Spring Boot)
                                                                             │  jobs + metadata ─► PostgreSQL
                                                                             │  image bytes     ─► ImageStorage (local disk)
                                                                             └─ ImageGenerationProvider
                                                                                  ├─ python-http / remote-http ─HTTP─► FastAPI inference server ─► YOUR model (GPU)
                                                                                  └─ mock (simulated placeholders, dev only)
 * see "Integrating clients" – never ship a privileged key inside an app.
```

```
myimageai/
├── backend/                      Kotlin / Spring Boot 3.3 / Gradle Kotlin DSL / Java 21
│   ├── build.gradle.kts, Dockerfile
│   └── src/main/kotlin/ai/myimage/
│       ├── config/     AppProperties (all settings), WiringConfig (picks provider/storage/queue, OpenAPI)
│       ├── common/     error model, global exception handler, request-ID + request-size filters
│       ├── security/   API keys (hashed), auth filter, rate limiter, CORS, admin key endpoints
│       ├── models/     model registry + per-model validation, /models and /health controllers
│       ├── provider/   ImageGenerationProvider, PythonHttpProvider, MockProvider
│       ├── storage/    ImageStorage (local), image validation, signed URLs, retention job
│       └── jobs/       entities, queue, worker, job service, /images/generations controller
│   └── src/main/resources/  application.yml, application-prod.yml, logback-spring.xml, db/migration/V1__initial_schema.sql
│   └── src/test/kotlin/     ApiFlowTest, ModelFailureTest
├── inference-server/             Python / FastAPI template (contract + adapters + tests + Dockerfile)
├── docs/requests.http            HTTP examples (IntelliJ / VS Code REST Client)
├── docker-compose.yml, .env.example, .gitignore, models/ (mount your checkpoints here)
```

**Versions:** Kotlin 1.9.25, Spring Boot 3.3.5, Java 21, springdoc-openapi 2.6.0, Flyway 10 (via Boot), PostgreSQL 16,
Gradle 8.10.x, Python 3.12, FastAPI 0.115, Pydantic 2.9, Pillow 11.

## Quick start

### A. Docker Compose (PostgreSQL + inference server + backend)
```bash
cp .env.example .env
# edit .env: replace every REPLACE_ME (use: openssl rand -hex 32)
docker compose up --build
curl http://localhost:8080/api/v1/health
```
With `INFERENCE_ADAPTER=mock` (default) the whole chain works end to end but returns **simulated** images.

### B. Backend only, no Docker (H2 dev database, mock provider)
Requires Java 21 and Gradle 8.10+ (no wrapper is included because it could not be generated here; run `gradle wrapper` once).
```bash
cd backend
export ADMIN_BOOTSTRAP_API_KEY=$(openssl rand -hex 32)   # remember it: it is your admin key
gradle bootRun                                            # http://localhost:8080  (Swagger UI: /swagger-ui.html)
gradle test
```

### C. Inference server only
```bash
cd inference-server
python -m venv .venv && . .venv/bin/activate
pip install -r requirements-dev.txt
pytest
INFERENCE_ADAPTER=mock uvicorn app.main:app --port 8001
```
To use it from the backend: `AI_PROVIDER=python-http AI_INFERENCE_URL=http://localhost:8001`.

## Plugging in your trained model

1. Open `inference-server/app/adapters/custom.py` and implement `load()` and `generate()` for **your** architecture
   (the file explains the contract and sketches a Diffusers example). A `.pt` / `.safetensors` file is only weights;
   nothing here can load it without your model code.
2. Add your ML dependencies to `inference-server/requirements.txt`; for GPU, base the Dockerfile on a CUDA/PyTorch image.
3. Put the checkpoint in `./models/` and set `INFERENCE_ADAPTER=custom`, `AI_MODEL_PATH=/models/latest.pt`, `AI_MODEL_ID=myimageai-v1`.
4. Adjust the model's limits/capabilities (max size, steps, negative prompt/seed support) in `backend/src/main/resources/application.yml` under `app.models`
   (or via the `AI_*` env vars). Add more entries to serve several model versions.

Until `load()` is implemented the inference server stays up, `GET /api/v1/health` shows `DEGRADED`, and jobs fail with `MODEL_UNAVAILABLE` after retries.

**Remote endpoint:** any HTTP service that implements the same two endpoints works with `AI_PROVIDER=remote-http`:
`GET /v1/health` → `{"status":"ok","model_loaded":true}` and `POST /v1/generate` with
`{model_id,prompt,negative_prompt,width,height,steps,seed,num_images}` → `{"model_id":..,"simulated":false,"images":[{"mime_type":"image/png","data_base64":"..","width":..,"height":..,"seed":..}]}`;
bearer token from `AI_INFERENCE_TOKEN`.

**Local runtime:** implement `ImageGenerationProvider` (e.g. for ONNX) and add one branch in `WiringConfig.imageGenerationProvider`.

## Configuration (environment variables)

| Variable | Purpose | Default |
|---|---|---|
| `AI_PROVIDER` | `mock`, `python-http`, `remote-http` | `mock` |
| `AI_INFERENCE_URL`, `AI_INFERENCE_TOKEN`, `AI_TIMEOUT_SECONDS` | inference server location / bearer token / per-request timeout | `http://localhost:8001`, none, `120` |
| `AI_MODEL_ID` | id exposed by the API and sent to the inference server | `myimageai-v1` |
| `AI_MODEL_PATH` | checkpoint path **inside the inference container** (read by the Python server, not the backend) | `/models/latest.pt` |
| `AI_MIN_SIZE`, `AI_MAX_SIZE`, `AI_DIMENSION_MULTIPLE`, `AI_MAX_STEPS`, `AI_MAX_IMAGES`, `AI_SUPPORTS_NEGATIVE_PROMPT`, `AI_SUPPORTS_SEED` | limits/capabilities of the default model | 256, 1024, 8, 50, 4, true, true |
| `ADMIN_BOOTSTRAP_API_KEY` | first admin key (≥32 chars, not `REPLACE_ME…`) | unset |
| `URL_SIGNING_SECRET` | HMAC secret for image URLs (**required** in `prod`) | random per process (dev) |
| `PUBLIC_BASE_URL`, `SIGNED_URL_TTL_SECONDS` | absolute image URLs; URL lifetime | empty, `900` |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | database (H2 file in dev, PostgreSQL in prod) | H2 |
| `STORAGE_LOCAL_PATH`, `IMAGE_RETENTION_DAYS`, `MAX_IMAGE_BYTES` | image storage, retention (0 = forever), size cap | `./data/images`, 30, 20 MB |
| `WORKER_CONCURRENCY`, `JOB_MAX_ATTEMPTS`, `SYNC_WAIT_SECONDS` | workers, retries on `MODEL_UNAVAILABLE`, max `?wait=true` time | 2, 3, 20 |
| `DEFAULT_RATE_LIMIT_PER_MINUTE`, `DEFAULT_DAILY_QUOTA`, `DEFAULT_MAX_CONCURRENT_JOBS` | defaults for new keys (overridable per key) | 60, 200, 2 |
| `CORS_ALLOWED_ORIGINS` | comma-separated allowlist; empty = none; `*` is ignored | empty |
| `SWAGGER_ENABLED` | OpenAPI/Swagger UI (off by default in `prod`) | true |
| `SPRING_PROFILES_ACTIVE` | `prod` enforces the signing secret and disables Swagger by default | – |

## Using the API (curl)

```bash
HOST=http://localhost:8080
ADMIN=<your ADMIN_BOOTSTRAP_API_KEY>

# 1. Admin creates a client key. The secret ("api_key") is shown ONCE; only its SHA-256 hash is stored.
curl -s -X POST $HOST/api/v1/admin/api-keys -H "Authorization: Bearer $ADMIN" \
  -H 'Content-Type: application/json' -d '{"name":"my-discord-bot","rate_limit_per_minute":30,"daily_quota":200}'
KEY=mia_...   # from the response

# 2. Submit a job (asynchronous, returns 202)
curl -s -X POST $HOST/api/v1/images/generations -H "Authorization: Bearer $KEY" \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: order-1001' \
  -d '{"model":"myimageai-v1","prompt":"An original anime city at sunset, cinematic lighting","negative_prompt":"blurry, low quality","width":512,"height":512,"steps":20,"seed":42,"num_images":1}'
# → {"id":"<JOB_ID>","status":"QUEUED",...}   (add ?wait=true to wait up to ~20 s for short jobs)

# 3. Poll until status is COMPLETED, FAILED or CANCELLED
curl -s $HOST/api/v1/images/generations/<JOB_ID> -H "Authorization: Bearer $KEY"

# 4. Get image info and download. URLs are signed and expire (default 15 min); no API key needed to fetch.
curl -s $HOST/api/v1/images/generations/<JOB_ID>/result -H "Authorization: Bearer $KEY"
curl -o out.png "<images[0].url from the result>"

# History (paginated), cancel a queued job, revoke a key
curl -s "$HOST/api/v1/images/generations/history?page=0&page_size=20" -H "Authorization: Bearer $KEY"
curl -s -X DELETE $HOST/api/v1/images/generations/<JOB_ID> -H "Authorization: Bearer $KEY"
curl -s -X DELETE $HOST/api/v1/admin/api-keys/<KEY_ID> -H "Authorization: Bearer $ADMIN"
```

Errors always look like `{"error":{"code":"VALIDATION_ERROR","message":"…","request_id":"…","details":{…}}}`.
Codes: `AUTHENTICATION_REQUIRED`, `INVALID_API_KEY`, `INSUFFICIENT_PERMISSIONS` (401/403), `VALIDATION_ERROR`, `INVALID_REQUEST_BODY` (400),
`MODEL_NOT_FOUND`, `JOB_NOT_FOUND` (404), `JOB_NOT_COMPLETED`, `JOB_ALREADY_FINISHED`, `CANCELLATION_NOT_SUPPORTED`, `IDEMPOTENCY_KEY_CONFLICT` (409),
`RATE_LIMIT_EXCEEDED`, `CONCURRENCY_LIMIT_EXCEEDED`, `QUOTA_EXCEEDED` (429, with `Retry-After`), `REQUEST_TOO_LARGE` (413).
Failed jobs carry `error.code` such as `MODEL_UNAVAILABLE`, `INFERENCE_TIMEOUT`, `INFERENCE_REJECTED`, `INFERENCE_FAILED`, `INVALID_MODEL_OUTPUT`.
Every response has an `X-Request-Id` header (send your own to correlate; it is echoed and logged).

**Job semantics.** `QUEUED → PROCESSING → COMPLETED | FAILED`, or `QUEUED → CANCELLED`. Only **queued** jobs can be cancelled; once the model is running,
`DELETE` returns `409 CANCELLATION_NOT_SUPPORTED` because the inference server cannot interrupt a running generation. `MODEL_UNAVAILABLE` is retried
(`JOB_MAX_ATTEMPTS`, linear backoff); timeouts and rejections are not. Jobs interrupted by a restart are re-queued on startup (single backend instance assumed).
Images the API returns are marked `"simulated": true` when they come from a mock provider/adapter.

## Integrating clients safely

* **Never put a privileged API key in an APK or browser JavaScript** – anyone can extract it. Keep keys on a server you control.
* **Android / websites:** call *your own* small backend (BFF) that holds the API key in its environment/secret store, authenticates your users, and forwards requests.
  Give that key tight limits (rate, quota, concurrency) and use a separate key per application so one can be revoked alone.
  Short-lived per-user tokens issued by your BFF are the right way to limit abuse from clients; this repo does not implement user accounts or token issuing.
* **Discord / Telegram bots and server apps:** store the key in an environment variable or secrets manager, never in source control.
* Image URLs are signed and expire, so they are safe to place in `<img>` tags or bot messages; request the result again for a fresh URL.
* Use HTTPS in production: terminate TLS at a reverse proxy/load balancer (Caddy, nginx, a cloud LB) in front of port 8080 and set `PUBLIC_BASE_URL=https://api.example.com`.

## Deployment notes

* Use the `prod` profile with PostgreSQL, set strong values for `ADMIN_BOOTSTRAP_API_KEY`, `URL_SIGNING_SECRET`, `AI_INFERENCE_TOKEN`, and keep the inference server on a private network.
* Rotating the bootstrap key adds a new admin key but does not remove the old one; revoke the old key via the API.
* Probes: `/actuator/health/liveness`, `/actuator/health/readiness` (public); metrics at `/actuator/metrics` and `/actuator/prometheus` require an admin key.
* Graceful shutdown: HTTP drains (`server.shutdown=graceful`), workers finish current jobs up to `JOB_SHUTDOWN_WAIT_SECONDS`, then are interrupted and re-queued on next start.
* Run **one** backend instance (the queue, rate limiter, and startup recovery are in-process). Scaling out needs the Redis queue and a shared rate limiter (see below).
* Back up PostgreSQL and the images volume.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Jobs fail with `MODEL_UNAVAILABLE` | inference server not running/reachable, wrong `AI_INFERENCE_URL`, token mismatch (check `docker compose logs inference`), or `custom` adapter not implemented |
| `GET /api/v1/health` shows `DEGRADED` | the `inference.detail` field says why (unreachable vs. no model loaded) |
| `401 INVALID_API_KEY` for the admin key | `ADMIN_BOOTSTRAP_API_KEY` was empty, <32 chars or a `REPLACE_ME…` placeholder – see startup log |
| Backend refuses to start in `prod` | `URL_SIGNING_SECRET` missing/short |
| Image URLs return 403 | expired, or the backend restarted without a fixed `URL_SIGNING_SECRET` |
| `INFERENCE_TIMEOUT` | raise `AI_TIMEOUT_SECONDS`; check GPU load |
| Mock images have no text | JVM has no fonts; stripes still mark them as simulated (the Docker image installs fonts) |

## What is verified / what is not

* **Not verified:** Kotlin compilation, Kotlin tests, Docker builds, PostgreSQL migrations (written for PostgreSQL and H2 PostgreSQL-mode), Python tests, the full request-to-result flow.
  Only syntax checks of the Python files and a bracket-balance check of the Kotlin files were run.
* **Needs you:** your model code in `custom.py`, a GPU host (if the model needs it), real secrets, TLS/domain, and a first `gradle test` run to catch compile issues.
* **Not implemented (by design, to keep the core working):**
  * S3-compatible storage – only local disk exists; implement `ImageStorage` (`save/read/delete`) with the AWS SDK and add a branch in `WiringConfig.imageStorage`.
  * Redis job queue – only the in-process queue exists; implement `JobQueue` and add a branch in `WiringConfig.jobQueue`.
  * Local (in-JVM) inference runtime – only the `ImageGenerationProvider` interface exists.
  * Webhooks, usage statistics, billing, SDKs, dashboard, user accounts / short-lived client tokens.
  * Request-size limit applies to the declared `Content-Length`; chunked uploads without a length are bounded only by prompt-length validation.
  * Postman collection: `docs/requests.http` and the curl examples above are provided instead.
  * No Gradle wrapper (could not be generated offline). Output is JSON-per-line-key=value logging, not JSON; API-key-shaped strings are redacted from log messages.
  * Only PNG and JPEG outputs are accepted (validated by content, size, and dimensions).
