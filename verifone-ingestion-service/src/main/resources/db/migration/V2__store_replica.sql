-- =====================================================================================
-- master.stores becomes a full replica of the VERIFONE rows of auth_db core.stores,
-- refreshed only by POST /api/ingestion/verifone/stores/sync (owner, 2026-10-22).
-- store_id = auth_db core.stores.store_id (V1 comments that name platform_db are historical).
-- Fetches read only this table; auth_db is read only during a sync.
-- A store that is no longer VERIFONE (or is gone) in auth_db becomes INACTIVE here; rows are
-- never deleted (cpj / ingest rows reference them).
-- Valid as NOT NULL without defaults: the table is empty when this runs on a new database.
-- =====================================================================================
ALTER TABLE master.stores
    ADD COLUMN store_name text        NOT NULL,
    ADD COLUMN s3_folder  text        NOT NULL,
    ADD COLUMN timezone   text        NOT NULL,
    ADD COLUMN status     text        NOT NULL,
    ADD COLUMN synced_at  timestamptz NOT NULL,
    ADD CONSTRAINT uq_master_stores_s3     UNIQUE (s3_folder),
    ADD CONSTRAINT ck_master_stores_name   CHECK (length(btrim(store_name)) BETWEEN 1 AND 200),
    ADD CONSTRAINT ck_master_stores_s3     CHECK (s3_folder ~ '^[^/].*/$'),
    ADD CONSTRAINT ck_master_stores_status CHECK (status IN ('ACTIVE', 'INACTIVE'));
