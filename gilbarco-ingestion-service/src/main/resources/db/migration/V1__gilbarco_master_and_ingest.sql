-- =====================================================================================
-- gilbarco_db: shared schemas (GILBARCO_DATABASE_DESIGN.md sections 3 and 4).
--   master: the store replica (from auth_db core.stores, POST /stores/sync) and departments.
--   ingest: what was downloaded from S3, what its manifest listed, every fetch and its load checks.
-- File-type schemas (pjr, later mcm, msm, fgm, ...) live in their own migrations.
-- =====================================================================================
CREATE SCHEMA master;
CREATE SCHEMA ingest;

-- -------------------------------------------------------------------------------------
-- Replica of the GILBARCO rows of auth_db core.stores, refreshed only by
-- POST /api/ingestion/gilbarco/stores/sync. store_id = auth_db store_id. A store that is no longer
-- GILBARCO (or is gone) becomes INACTIVE here; rows are never deleted (loaded data references them).
-- -------------------------------------------------------------------------------------
CREATE TABLE master.stores (
    store_id         bigint       PRIMARY KEY,
    store_code       text         NOT NULL,
    store_name       text         NOT NULL,
    s3_folder        text         NOT NULL,                 -- wolf_800_NY-13208/wolf_800_NY-13208/
    timezone         text         NOT NULL,
    status           text         NOT NULL,
    synced_at        timestamptz  NOT NULL,
    copied_at        timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_master_stores_code   UNIQUE (store_code),
    CONSTRAINT uq_master_stores_s3     UNIQUE (s3_folder),
    CONSTRAINT ck_master_stores_id     CHECK (store_id > 0),
    CONSTRAINT ck_master_stores_code   CHECK (store_code ~ '^[A-Za-z0-9][A-Za-z0-9_-]*$'),
    CONSTRAINT ck_master_stores_name   CHECK (length(btrim(store_name)) BETWEEN 1 AND 200),
    CONSTRAINT ck_master_stores_s3     CHECK (s3_folder ~ '^[^/].*/$'),
    CONSTRAINT ck_master_stores_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

-- -------------------------------------------------------------------------------------
-- A store's departments, learned from its files (nothing typed in). The name stays empty until a
-- department ring names it (scanned items carry only the number). sales_class follows the name
-- (master.sales_class_for): LOTTO / LOTTERY = lottery_sale, with PAID OUT / PAYOUT = lottery_payout.
-- Wolf: 5 LOTTO SCRATCH OFF and 57 LOTTO ONLINE = lottery_sale, 6 LOTTO PAID OUT = lottery_payout.
-- -------------------------------------------------------------------------------------
CREATE FUNCTION master.sales_class_for(dept_name text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE
    RETURN CASE WHEN dept_name ~* '(LOTTO|LOTTERY)' AND dept_name ~* '(PAID[[:space:]]*OUT|PAY[[:space:]]*OUT)' THEN 'lottery_payout'
                WHEN dept_name ~* '(LOTTO|LOTTERY)' THEN 'lottery_sale'
                ELSE 'merchandise' END;

CREATE TABLE master.departments (
    department_id    bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    store_id         bigint       NOT NULL REFERENCES master.stores (store_id) ON DELETE RESTRICT,
    store_code       text         NOT NULL,
    dept_number      integer      NOT NULL,
    dept_name        text,
    sales_class      text         NOT NULL DEFAULT 'merchandise',
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_master_departments UNIQUE (store_id, dept_number),
    CONSTRAINT ck_master_departments_class CHECK (sales_class IN ('merchandise', 'lottery_sale', 'lottery_payout', 'fuel'))
);
CREATE INDEX ix_master_departments_store ON master.departments (store_code, dept_number);

-- -------------------------------------------------------------------------------------
-- One downloaded zip: s3://<bucket>/<s3_folder><yyyy>/<month>/<yyyy-mm-dd>/<TYPE>/<..>_<TYPE>_<date>_v<n>.zip
-- The manifest's store/type/businessDate/version equal the key; the loader refuses the zip otherwise.
-- -------------------------------------------------------------------------------------
CREATE TABLE ingest.s3_objects (
    s3_object_id         bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    store_id             bigint       NOT NULL REFERENCES master.stores (store_id) ON DELETE RESTRICT,
    file_type            text         NOT NULL,
    business_date        date         NOT NULL,
    version_no           integer      NOT NULL,
    bucket               text         NOT NULL,
    object_key           text         NOT NULL,
    etag                 text         NOT NULL,
    size_bytes           bigint       NOT NULL,
    last_modified        timestamptz  NOT NULL,             -- S3 LastModified
    manifest_created_at  timestamptz  NOT NULL,             -- manifest.json createdAt
    mirror_version       text         NOT NULL,             -- manifest.json fastMirrorVersion
    downloaded_at        timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_s3_objects_object   UNIQUE (bucket, object_key, etag),
    CONSTRAINT uq_s3_objects_day      UNIQUE (s3_object_id, store_id, business_date),
    CONSTRAINT ck_s3_objects_type     CHECK (file_type IN ('PJR', 'MCM', 'MSM', 'FGM', 'TLM', 'ISM', 'FPM', 'TPM')),
    CONSTRAINT ck_s3_objects_version  CHECK (version_no > 0),
    CONSTRAINT ck_s3_objects_key      CHECK (object_key ~* ('_' || file_type || '_' || to_char(business_date, 'YYYY-MM-DD')
                                                            || '_v' || version_no || '\.zip$')),
    CONSTRAINT ck_s3_objects_size     CHECK (size_bytes > 0),
    CONSTRAINT ck_s3_objects_text     CHECK (btrim(bucket) <> '' AND btrim(etag) <> '' AND btrim(mirror_version) <> '')
);
CREATE INDEX ix_s3_objects_day ON ingest.s3_objects (store_id, file_type, business_date, version_no);

-- One file listed in the zip's manifest.json, with the checksum the loader verified.
CREATE TABLE ingest.s3_object_files (
    s3_object_id     bigint       NOT NULL REFERENCES ingest.s3_objects (s3_object_id) ON DELETE CASCADE,
    file_path        text         NOT NULL,                 -- 2026/september/2026-09-24/PJR/PJR3402609240004181533189.xml
    size_bytes       bigint       NOT NULL,
    sha256           char(64)     NOT NULL,
    modified_at      timestamptz  NOT NULL,
    PRIMARY KEY (s3_object_id, file_path),
    CONSTRAINT ck_s3_object_files_path  CHECK (file_path !~ '(^/|\.\.)'),   -- zip-slip guard
    CONSTRAINT ck_s3_object_files_size  CHECK (size_bytes >= 0),
    CONSTRAINT ck_s3_object_files_sha   CHECK (sha256 ~ '^[0-9a-f]{64}$')
);

-- -------------------------------------------------------------------------------------
-- One fetch of one store's business day of one file type.
-- CLAIMED (accepted, queued) -> RUNNING (worker picked it up) -> LOADED | REFUSED | NOT_FOUND | FAILED.
-- REPLACE: delete what is loaded for that store + day + type, load the highest _vN zip.
-- REFUSE:  do nothing if anything is loaded for that store + day + type.
-- Status rows are written in their own short transactions, so a failed (rolled back) load still
-- leaves its FAILED row and message.
-- -------------------------------------------------------------------------------------
CREATE TABLE ingest.fetch_requests (
    fetch_request_id bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    store_id         bigint       NOT NULL REFERENCES master.stores (store_id) ON DELETE RESTRICT,
    business_date    date         NOT NULL,
    file_type        text         NOT NULL DEFAULT 'PJR',
    mode             text         NOT NULL,
    status           text         NOT NULL DEFAULT 'CLAIMED',
    message          text,
    s3_object_id     bigint       REFERENCES ingest.s3_objects (s3_object_id) ON DELETE SET NULL,
    requested_by     bigint       NOT NULL,                 -- hor-auth admin id (other database, checked by the API)
    requested_at     timestamptz  NOT NULL DEFAULT now(),
    finished_at      timestamptz,
    CONSTRAINT ck_fetch_requests_type   CHECK (file_type IN ('PJR', 'MCM', 'MSM', 'FGM', 'TLM', 'ISM', 'FPM', 'TPM')),
    CONSTRAINT ck_fetch_requests_mode   CHECK (mode IN ('REFUSE', 'REPLACE')),
    CONSTRAINT ck_fetch_requests_status CHECK (status IN ('CLAIMED', 'RUNNING', 'LOADED', 'REFUSED', 'NOT_FOUND', 'FAILED')),
    CONSTRAINT ck_fetch_requests_user   CHECK (requested_by > 0),
    CONSTRAINT ck_fetch_requests_finish CHECK ((status IN ('CLAIMED', 'RUNNING')) = (finished_at IS NULL)),
    CONSTRAINT ck_fetch_requests_order  CHECK (finished_at IS NULL OR finished_at >= requested_at),
    CONSTRAINT ck_fetch_requests_failed CHECK (status <> 'FAILED' OR btrim(coalesce(message, '')) <> '')
);
-- At most one open fetch per store + day + file type: two admins clicking at once cannot load the
-- same day twice (the second gets 409 FETCH_RUNNING).
CREATE UNIQUE INDEX uq_fetch_requests_running ON ingest.fetch_requests (store_id, file_type, business_date)
    WHERE status IN ('CLAIMED', 'RUNNING');
CREATE INDEX ix_fetch_requests_store_day ON ingest.fetch_requests (store_id, file_type, business_date);
CREATE INDEX ix_fetch_requests_requested ON ingest.fetch_requests (requested_at DESC, fetch_request_id DESC);

-- Load checks of a fetch, written in the load transaction (a hard failure rolls everything back).
CREATE TABLE ingest.load_checks (
    check_id         bigint        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    fetch_request_id bigint        NOT NULL REFERENCES ingest.fetch_requests (fetch_request_id) ON DELETE CASCADE,
    check_name       text          NOT NULL,
    expected         numeric(14,4),
    actual           numeric(14,4),
    passed           boolean       NOT NULL,
    hard             boolean       NOT NULL,
    detail           text,
    checked_at       timestamptz   NOT NULL DEFAULT now()
);
CREATE INDEX ix_load_checks_fetch ON ingest.load_checks (fetch_request_id);
