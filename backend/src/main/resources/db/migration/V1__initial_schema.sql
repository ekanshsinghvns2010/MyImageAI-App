CREATE TABLE api_keys (
    id                    UUID                     NOT NULL PRIMARY KEY,
    name                  VARCHAR(100)             NOT NULL,
    key_prefix            VARCHAR(16)              NOT NULL,
    key_hash              VARCHAR(64)              NOT NULL,
    role                  VARCHAR(16)              NOT NULL,
    rate_limit_per_minute INTEGER                  NOT NULL,
    daily_quota           INTEGER                  NOT NULL,
    max_concurrent_jobs   INTEGER                  NOT NULL,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at            TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_api_keys_hash UNIQUE (key_hash)
);

CREATE TABLE generation_jobs (
    id               UUID                     NOT NULL PRIMARY KEY,
    client_id        UUID                     NOT NULL,
    status           VARCHAR(16)              NOT NULL,
    model_id         VARCHAR(100)             NOT NULL,
    prompt           VARCHAR(4000)            NOT NULL,
    negative_prompt  VARCHAR(4000),
    width            INTEGER                  NOT NULL,
    height           INTEGER                  NOT NULL,
    steps            INTEGER                  NOT NULL,
    seed             BIGINT,
    num_images       INTEGER                  NOT NULL,
    idempotency_key  VARCHAR(128),
    attempts         INTEGER                  NOT NULL,
    error_code       VARCHAR(64),
    error_message    VARCHAR(500),
    simulated        BOOLEAN,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at       TIMESTAMP WITH TIME ZONE,
    completed_at     TIMESTAMP WITH TIME ZONE
);

CREATE INDEX ix_jobs_client_created ON generation_jobs (client_id, created_at);
CREATE INDEX ix_jobs_status ON generation_jobs (status);
CREATE UNIQUE INDEX uq_jobs_idempotency ON generation_jobs (client_id, idempotency_key);

CREATE TABLE generated_images (
    id           UUID                     NOT NULL PRIMARY KEY,
    job_id       UUID                     NOT NULL,
    client_id    UUID                     NOT NULL,
    image_index  INTEGER                  NOT NULL,
    storage_key  VARCHAR(255)             NOT NULL,
    mime_type    VARCHAR(64)              NOT NULL,
    width        INTEGER                  NOT NULL,
    height       INTEGER                  NOT NULL,
    size_bytes   BIGINT                   NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_images_job FOREIGN KEY (job_id) REFERENCES generation_jobs (id) ON DELETE CASCADE
);

CREATE INDEX ix_images_job ON generated_images (job_id);
CREATE INDEX ix_images_created ON generated_images (created_at);
