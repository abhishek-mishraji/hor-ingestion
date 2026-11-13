-- =====================================================================================
-- verifone_db, owned by verifone-ingestion-service. The whole schema in one migration (owner,
-- 2026-10-22: development phase, no migration history; V1..V10 were merged into this file).
--   ingest  what was fetched from S3, by whom, and with what result      section 1
--   master  codes and names learned from the POS files (departments, ...)  section 2
--   cpj     the contents of each CPJ file (one closed business day)        section 3
--   views   name history                                                   section 4
--   report  read-only report views (later phase) + reporting_svc grants    sections 5, 6
--   lottery class of a department, fixed by its name                       section 7
-- Same table rules as platform_db: see docs/database-design.md.
-- From now on a change to an applied schema is a new V<n>__*.sql again.
-- =====================================================================================

CREATE SCHEMA master;
CREATE SCHEMA ingest;

-- -------------------------------------------------------------------------------------
-- Local copy of platform_db.core.stores (id + code only), so every table in this database
-- can have a real foreign key to its store. verifone-ingestion-service inserts the row from
-- platform_db before a store's first fetch; store_code never changes there, so the copy
-- cannot go stale. Never edited by hand.
-- -------------------------------------------------------------------------------------
CREATE TABLE master.stores (
    store_id         bigint       PRIMARY KEY,              -- = platform_db core.stores.store_id
    store_code       text         NOT NULL,
    copied_at        timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_master_stores_code  UNIQUE (store_code),
    CONSTRAINT ck_master_stores_id    CHECK (store_id > 0),
    CONSTRAINT ck_master_stores_code  CHECK (store_code ~ '^[A-Za-z0-9][A-Za-z0-9_-]*$')
);

-- -------------------------------------------------------------------------------------
-- One downloaded zip: s3://<bucket>/<s3_folder><yyyy>/<month>/<yyyy-mm-dd>/CPJ/<..>_CPJ_<date>_v<n>.zip
-- The manifest's store/type/businessDate/version must equal the key; the loader refuses
-- the zip otherwise. fileCount is not stored: it is the number of s3_object_files rows.
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
    CONSTRAINT uq_s3_objects_day      UNIQUE (s3_object_id, store_id, business_date),  -- target for cpj.cpj_files
    CONSTRAINT ck_s3_objects_type     CHECK (file_type IN ('CPJ')),
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
    file_path        text         NOT NULL,                 -- 2026/october/2026-10-11/CPJ/CPJ2026-10-11-617.xml
    size_bytes       bigint       NOT NULL,
    sha256           char(64)     NOT NULL,
    modified_at      timestamptz  NOT NULL,
    CONSTRAINT pk_s3_object_files       PRIMARY KEY (s3_object_id, file_path),
    CONSTRAINT ck_s3_object_files_path  CHECK (file_path !~ '(^/|\.\.)'),   -- no absolute paths, no "..": zip-slip guard
    CONSTRAINT ck_s3_object_files_size  CHECK (size_bytes >= 0),
    CONSTRAINT ck_s3_object_files_sha   CHECK (sha256 ~ '^[0-9a-f]{64}$')
);

-- -------------------------------------------------------------------------------------
-- One fetch of one business day: one click of Fetch in the admin console (days are fetched one
-- at a time; no date-range backfill, owner 2026-10-18).
-- REPLACE: delete what is loaded for that store + day, load the highest _vN zip.
-- REFUSE:  do nothing if anything is loaded for that store + day, whatever the version.
-- Status rows are written in their own short transactions, so a failed load (rolled back)
-- still leaves its FAILED row and message.
-- -------------------------------------------------------------------------------------
CREATE TABLE ingest.fetch_requests (
    fetch_request_id bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    store_id         bigint       NOT NULL REFERENCES master.stores (store_id) ON DELETE RESTRICT,
    file_type        text         NOT NULL,
    business_date    date         NOT NULL,
    mode             text         NOT NULL,
    requested_by     bigint       NOT NULL,                 -- platform_db core.platform_admins.admin_id: other database, checked by the API
    requested_at     timestamptz  NOT NULL DEFAULT now(),
    status           text         NOT NULL DEFAULT 'RUNNING',
    s3_object_id     bigint       REFERENCES ingest.s3_objects (s3_object_id) ON DELETE RESTRICT,
    finished_at      timestamptz,
    message          text,
    CONSTRAINT ck_fetch_requests_type     CHECK (file_type IN ('CPJ')),
    CONSTRAINT ck_fetch_requests_mode     CHECK (mode IN ('REPLACE', 'REFUSE')),
    CONSTRAINT ck_fetch_requests_user     CHECK (requested_by > 0),
    CONSTRAINT ck_fetch_requests_status   CHECK (status IN ('RUNNING', 'LOADED', 'REFUSED', 'NOT_FOUND', 'FAILED')),
    CONSTRAINT ck_fetch_requests_finish   CHECK ((status = 'RUNNING') = (finished_at IS NULL)),
    CONSTRAINT ck_fetch_requests_order    CHECK (finished_at IS NULL OR finished_at >= requested_at),
    CONSTRAINT ck_fetch_requests_loaded   CHECK (status <> 'LOADED' OR s3_object_id IS NOT NULL),
    CONSTRAINT ck_fetch_requests_failed   CHECK (status <> 'FAILED' OR btrim(coalesce(message, '')) <> '')
);
-- At most one running fetch per store + day + file type: two admins clicking at once cannot
-- load the same day twice. (On start-up the backend marks RUNNING rows of a crashed run FAILED.)
CREATE UNIQUE INDEX uq_fetch_requests_running ON ingest.fetch_requests (store_id, file_type, business_date)
    WHERE status = 'RUNNING';
CREATE INDEX ix_fetch_requests_day       ON ingest.fetch_requests (store_id, business_date, requested_at);
CREATE INDEX ix_fetch_requests_s3_object ON ingest.fetch_requests (s3_object_id) WHERE s3_object_id IS NOT NULL;


-- #####################################################################################
-- 2. master: codes and names (was V2)
-- #####################################################################################

-- =====================================================================================
-- master: the codes a store's POS uses, learned from its files (nothing typed in;
-- departments.lottery_class is derived from the department name, section 7).
--
-- Pattern: <thing> holds the code, which never changes (department 29 of store 8730_IN-47872).
--          <thing>_versions holds each distinct name/description seen for that code
--          ("Lotto"). If the POS renames it, a new version row appears and old lines keep
--          pointing at the old version. Lines reference the version, so every line keeps
--          the exact name printed on it, with no name repeated on every line.
--          The dates a name was in use come from the lines: views in V4.
-- Codes are per store: department 29 at one store is unrelated to department 29 at another.
-- A code or version is inserted the first time a file uses it, inside the load transaction
-- (INSERT ... ON CONFLICT DO NOTHING), and is never deleted while lines use it (RESTRICT).
-- UNIQUE ... NULLS NOT DISTINCT: two versions that differ only by NULL vs NULL are the same.
-- =====================================================================================

-- Department: trlDept/@number. Lotto / Lotto Payout confirmed by the owner (dept 29 / 28 at Tiki).
CREATE TABLE master.departments (
    department_id    bigint   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    store_id         bigint   NOT NULL REFERENCES master.stores (store_id) ON DELETE RESTRICT,
    dept_number      integer  NOT NULL,
    lottery_class    text,                                  -- from the department name (section 7)
    CONSTRAINT uq_departments         UNIQUE (store_id, dept_number),
    CONSTRAINT ck_departments_number  CHECK (dept_number >= 0),
    CONSTRAINT ck_departments_lottery CHECK (lottery_class IN ('LOTTERY_SALE', 'LOTTERY_PAYOUT'))
);
CREATE TABLE master.department_versions (
    department_version_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    department_id    bigint   NOT NULL REFERENCES master.departments (department_id) ON DELETE RESTRICT,
    name             text     NOT NULL,                     -- trlDept text: Lotto
    dept_type        text     NOT NULL,                     -- trlDept/@type
    CONSTRAINT uq_department_versions       UNIQUE (department_id, name, dept_type),
    CONSTRAINT ck_department_versions_type  CHECK (dept_type IN ('norm', 'neg', 'fuel'))
);

-- Category: trlCat/@number, text e.g. GROC NO-TAX
CREATE TABLE master.categories (
    category_id      bigint   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    store_id         bigint   NOT NULL REFERENCES master.stores (store_id) ON DELETE RESTRICT,
    category_number  integer  NOT NULL,
    CONSTRAINT uq_categories        UNIQUE (store_id, category_number),
    CONSTRAINT ck_categories_number CHECK (category_number >= 0)
);
CREATE TABLE master.category_versions (
    category_version_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    category_id      bigint   NOT NULL REFERENCES master.categories (category_id) ON DELETE RESTRICT,
    name             text     NOT NULL,
    CONSTRAINT uq_category_versions UNIQUE (category_id, name)
);

-- Item: trlUPC (14 digits, leading zeros kept) + trlModifier (3 digits). Price is NOT here:
-- it is a fact of each line.
CREATE TABLE master.items (
    item_id          bigint   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    store_id         bigint   NOT NULL REFERENCES master.stores (store_id) ON DELETE RESTRICT,
    upc              text     NOT NULL,
    upc_modifier     text     NOT NULL,
    CONSTRAINT uq_items          UNIQUE (store_id, upc, upc_modifier),
    CONSTRAINT ck_items_upc      CHECK (upc ~ '^[0-9]{1,14}$'),
    CONSTRAINT ck_items_modifier CHECK (upc_modifier ~ '^[0-9]{1,3}$')
);
CREATE TABLE master.item_versions (
    item_version_id  bigint   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    item_id          bigint   NOT NULL REFERENCES master.items (item_id) ON DELETE RESTRICT,
    description      text     NOT NULL,                     -- trlDesc
    CONSTRAINT uq_item_versions UNIQUE (item_id, description)
);

-- Cashier: cashier/@sysid, text = name (CORPORATE), @empNum
CREATE TABLE master.cashiers (
    cashier_id       bigint   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    store_id         bigint   NOT NULL REFERENCES master.stores (store_id) ON DELETE RESTRICT,
    cashier_sysid    integer  NOT NULL,
    CONSTRAINT uq_cashiers UNIQUE (store_id, cashier_sysid)
);
CREATE TABLE master.cashier_versions (
    cashier_version_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cashier_id       bigint   NOT NULL REFERENCES master.cashiers (cashier_id) ON DELETE RESTRICT,
    name             text     NOT NULL,
    employee_number  integer,
    CONSTRAINT uq_cashier_versions UNIQUE NULLS NOT DISTINCT (cashier_id, name, employee_number)
);

-- Register: posNum (101/102 inside, 901-904 pumps, 0 = system).
-- physicalRegisterID equalled posNum on every ticket in the 20 sample days; the loader
-- refuses a file where one posNum shows two physical IDs (so this column stays a fact of the
-- register, not of the ticket).
CREATE TABLE master.registers (
    register_id          bigint   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    store_id             bigint   NOT NULL REFERENCES master.stores (store_id) ON DELETE RESTRICT,
    pos_num              integer  NOT NULL,
    physical_register_id integer,
    CONSTRAINT uq_registers     UNIQUE (store_id, pos_num),
    CONSTRAINT ck_registers_num CHECK (pos_num >= 0)
);

-- Tax: @sysid, @cat = name (STATE). The rate is a fact of each line/ticket.
CREATE TABLE master.taxes (
    tax_id           bigint   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    store_id         bigint   NOT NULL REFERENCES master.stores (store_id) ON DELETE RESTRICT,
    tax_sysid        integer  NOT NULL,
    CONSTRAINT uq_taxes UNIQUE (store_id, tax_sysid)
);
CREATE TABLE master.tax_versions (
    tax_version_id   bigint   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tax_id           bigint   NOT NULL REFERENCES master.taxes (tax_id) ON DELETE RESTRICT,
    name             text     NOT NULL,
    CONSTRAINT uq_tax_versions UNIQUE (tax_id, name)
);

-- Payment type: trpPaycode. CASH and Change share @mop="1", so the code is mop + paycode text.
CREATE TABLE master.payment_types (
    payment_type_id  bigint   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    store_id         bigint   NOT NULL REFERENCES master.stores (store_id) ON DELETE RESTRICT,
    mop_code         integer  NOT NULL,                     -- @mop
    paycode          text     NOT NULL,                     -- text: CASH, Change, DEBIT, CREDIT, MOBILE, CHECK, FOODSTAMP, ONLINE
    CONSTRAINT uq_payment_types       UNIQUE (store_id, mop_code, paycode),
    CONSTRAINT ck_payment_types_code  CHECK (mop_code >= 0 AND btrim(paycode) <> '')
);
CREATE TABLE master.payment_type_versions (
    payment_type_version_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payment_type_id  bigint   NOT NULL REFERENCES master.payment_types (payment_type_id) ON DELETE RESTRICT,
    mop_category     integer  NOT NULL,                     -- @cat
    nacs_tender_code text     NOT NULL,                     -- @nacstendercode
    nacs_tender_sub  text     NOT NULL,                     -- @nacstendersubcode
    CONSTRAINT uq_payment_type_versions UNIQUE (payment_type_id, mop_category, nacs_tender_code, nacs_tender_sub)
);

-- Card type: trpcCCName text (VISA, DEBIT, WRIGHT EXP, ...). @sysid is 0 for every name, so the
-- name is the code.
CREATE TABLE master.card_types (
    card_type_id     bigint   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    store_id         bigint   NOT NULL REFERENCES master.stores (store_id) ON DELETE RESTRICT,
    card_name        text     NOT NULL,
    CONSTRAINT uq_card_types      UNIQUE (store_id, card_name),
    CONSTRAINT ck_card_types_name CHECK (btrim(card_name) <> '')
);

-- Card: trpcAccount as the POS writes it: first 6 + masked middle + last 4 (446618FFFFFF3859).
-- The CHECK rejects an all-digit value, so a full card number can never be stored.
CREATE TABLE master.cards (
    card_id          bigint   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    store_id         bigint   NOT NULL REFERENCES master.stores (store_id) ON DELETE RESTRICT,
    masked_number    text     NOT NULL,
    CONSTRAINT uq_cards        UNIQUE (store_id, masked_number),
    CONSTRAINT ck_cards_masked CHECK (masked_number ~ '^[0-9FX*]{8,24}$' AND masked_number ~ '[FX*]')
);

-- Loyalty: trLoyaltyProgram/@programID (Club CITGO), trloAccount (masked card or 10-digit number).
CREATE TABLE master.loyalty_programs (
    loyalty_program_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    store_id         bigint   NOT NULL REFERENCES master.stores (store_id) ON DELETE RESTRICT,
    program_code     text     NOT NULL,
    CONSTRAINT uq_loyalty_programs      UNIQUE (store_id, program_code),
    CONSTRAINT ck_loyalty_programs_code CHECK (btrim(program_code) <> '')
);
CREATE TABLE master.loyalty_accounts (
    loyalty_account_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    loyalty_program_id bigint NOT NULL REFERENCES master.loyalty_programs (loyalty_program_id) ON DELETE RESTRICT,
    account_number   text     NOT NULL,
    CONSTRAINT uq_loyalty_accounts         UNIQUE (loyalty_program_id, account_number),
    CONSTRAINT uq_loyalty_accounts_program UNIQUE (loyalty_program_id, loyalty_account_id),  -- target for cpj.ticket_loyalty
    CONSTRAINT ck_loyalty_accounts_number  CHECK (btrim(account_number) <> '')
);

-- Fuel grade: fuelProd/@sysid, text (Regular, Diesel), @NAXMLFuelGradeID
CREATE TABLE master.fuel_grades (
    fuel_grade_id    bigint   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    store_id         bigint   NOT NULL REFERENCES master.stores (store_id) ON DELETE RESTRICT,
    grade_sysid      integer  NOT NULL,
    CONSTRAINT uq_fuel_grades UNIQUE (store_id, grade_sysid)
);
CREATE TABLE master.fuel_grade_versions (
    fuel_grade_version_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    fuel_grade_id    bigint   NOT NULL REFERENCES master.fuel_grades (fuel_grade_id) ON DELETE RESTRICT,
    name             text     NOT NULL,
    naxml_grade_id   text,
    CONSTRAINT uq_fuel_grade_versions UNIQUE NULLS NOT DISTINCT (fuel_grade_id, name, naxml_grade_id)
);

-- Fuel price level: fuelMOP/@sysid, text (CASH, CREDIT)
CREATE TABLE master.fuel_price_levels (
    fuel_price_level_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    store_id         bigint   NOT NULL REFERENCES master.stores (store_id) ON DELETE RESTRICT,
    level_sysid      integer  NOT NULL,
    CONSTRAINT uq_fuel_price_levels UNIQUE (store_id, level_sysid)
);
CREATE TABLE master.fuel_price_level_versions (
    fuel_price_level_version_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    fuel_price_level_id bigint NOT NULL REFERENCES master.fuel_price_levels (fuel_price_level_id) ON DELETE RESTRICT,
    name             text     NOT NULL,
    CONSTRAINT uq_fuel_price_level_versions UNIQUE (fuel_price_level_id, name)
);

-- Fuel service mode: fuelSvcMode/@sysid, text (SELF)
CREATE TABLE master.fuel_service_modes (
    fuel_service_mode_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    store_id         bigint   NOT NULL REFERENCES master.stores (store_id) ON DELETE RESTRICT,
    mode_sysid       integer  NOT NULL,
    CONSTRAINT uq_fuel_service_modes UNIQUE (store_id, mode_sysid)
);
CREATE TABLE master.fuel_service_mode_versions (
    fuel_service_mode_version_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    fuel_service_mode_id bigint NOT NULL REFERENCES master.fuel_service_modes (fuel_service_mode_id) ON DELETE RESTRICT,
    name             text     NOT NULL,
    CONSTRAINT uq_fuel_service_mode_versions UNIQUE (fuel_service_mode_id, name)
);

-- Fuel tier: fuelTier/@sysid (only in fuel price changes; the file gives no name)
CREATE TABLE master.fuel_tiers (
    fuel_tier_id     bigint   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    store_id         bigint   NOT NULL REFERENCES master.stores (store_id) ON DELETE RESTRICT,
    tier_sysid       integer  NOT NULL,
    CONSTRAINT uq_fuel_tiers UNIQUE (store_id, tier_sysid)
);

-- Mix-and-match promotion: trlPromotionID (+ @promotype), trlMatchName
CREATE TABLE master.promotions (
    promotion_id     bigint   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    store_id         bigint   NOT NULL REFERENCES master.stores (store_id) ON DELETE RESTRICT,
    promotion_code   text     NOT NULL,
    CONSTRAINT uq_promotions      UNIQUE (store_id, promotion_code),
    CONSTRAINT ck_promotions_code CHECK (btrim(promotion_code) <> '')
);
CREATE TABLE master.promotion_versions (
    promotion_version_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    promotion_id     bigint   NOT NULL REFERENCES master.promotions (promotion_id) ON DELETE RESTRICT,
    name             text     NOT NULL,                     -- trlMatchName
    promo_type       text     NOT NULL,                     -- @promotype: mixAndMatchOffer
    CONSTRAINT uq_promotion_versions UNIQUE (promotion_id, name, promo_type)
);

-- House account: trHouseAcct text = account number, @name (seen once, on a payout: Rafter)
CREATE TABLE master.house_accounts (
    house_account_id bigint   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    store_id         bigint   NOT NULL REFERENCES master.stores (store_id) ON DELETE RESTRICT,
    account_number   text     NOT NULL,
    CONSTRAINT uq_house_accounts        UNIQUE (store_id, account_number),
    CONSTRAINT ck_house_accounts_number CHECK (btrim(account_number) <> '')
);
CREATE TABLE master.house_account_versions (
    house_account_version_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    house_account_id bigint   NOT NULL REFERENCES master.house_accounts (house_account_id) ON DELETE RESTRICT,
    name             text     NOT NULL,
    CONSTRAINT uq_house_account_versions UNIQUE (house_account_id, name)
);


-- #####################################################################################
-- 3. cpj: contents of each CPJ file (was V3, with V5 / V6 / V7 folded in)
-- #####################################################################################

-- =====================================================================================
-- cpj: everything in a CPJ file (one closed business day of one store), as the POS wrote it.
-- No business rules here (what counts as a sale, lottery vs merchandise): those come later
-- in a separate report schema built on these tables.
--
-- Element inventory these tables were built from: 20 Tiki days (2026-04-16, 08-29,
-- 09-01..09-17, 10-11). Not stored on purpose: the journal log text (trans type="journal")
-- EXCEPT the cashier events in cpj.cashier_events, and the root-level <openCashier> /
-- <closeCashier> copies, which repeat the trans of the same name (the loader checks they agree).
--
-- Deleting a cpj_files row removes the whole day (ON DELETE CASCADE down the tree); this is
-- how a REPLACE fetch works. Facts point at master codes with ON DELETE RESTRICT.
--
-- Fixed value sets (trans_type, line_type, ...) are CHECK constraints: a value never seen
-- before makes the load fail with a clear message instead of being stored with an unknown
-- meaning. Descriptive values (card entry method, unit of measure) are free text.
-- =====================================================================================

CREATE SCHEMA cpj;

-- -------------------------------------------------------------------------------------
-- The day: <transSet> attributes and open/close times.
-- (s3_object_id, store_id, business_date) references ingest.s3_objects, so a day can never be
-- filed under a different store or date than the zip it came from.
-- -------------------------------------------------------------------------------------
CREATE TABLE cpj.cpj_files (
    cpj_file_id      bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    store_id         bigint       NOT NULL REFERENCES master.stores (store_id) ON DELETE RESTRICT,
    business_date    date         NOT NULL,                 -- date in the file name = business day that closed
    day_seq          integer      NOT NULL,                 -- transSet/@shortId = number in the file name (617)
    s3_object_id     bigint       NOT NULL,
    file_path        text         NOT NULL,                 -- path inside the zip
    fetch_request_id bigint       NOT NULL REFERENCES ingest.fetch_requests (fetch_request_id) ON DELETE RESTRICT,
    period_id        integer      NOT NULL,                 -- transSet/@periodID (2)
    period_name      text         NOT NULL,                 -- transSet/@periodname (Day)
    period_long_id   timestamptz  NOT NULL,                 -- transSet/@longId
    site_id          text         NOT NULL,                 -- transSet/@site (AB123); every trHeader/storeNumber equals it
    opened_at        timestamptz  NOT NULL,                 -- openedTime
    closed_at        timestamptz  NOT NULL,                 -- closedTime
    loaded_at        timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_cpj_files_day    UNIQUE (store_id, business_date, day_seq),
    CONSTRAINT uq_cpj_files_source UNIQUE (s3_object_id, file_path),
    CONSTRAINT fk_cpj_files_object FOREIGN KEY (s3_object_id, store_id, business_date)
        REFERENCES ingest.s3_objects (s3_object_id, store_id, business_date) ON DELETE RESTRICT,
    CONSTRAINT fk_cpj_files_file   FOREIGN KEY (s3_object_id, file_path)
        REFERENCES ingest.s3_object_files (s3_object_id, file_path) ON DELETE RESTRICT,
    CONSTRAINT ck_cpj_files_name   CHECK (file_path ~ ('(^|/)CPJ' || to_char(business_date, 'YYYY-MM-DD') || '-' || day_seq || '\.xml$')),
    CONSTRAINT ck_cpj_files_times  CHECK (closed_at > opened_at),
    CONSTRAINT ck_cpj_files_text   CHECK (btrim(period_name) <> '' AND btrim(site_id) <> '')
);
CREATE INDEX ix_cpj_files_fetch ON cpj.cpj_files (fetch_request_id);

-- startTotals / endTotals: the POS's never-resetting sales counters at open and close.
CREATE TABLE cpj.file_totals (
    cpj_file_id              bigint        NOT NULL REFERENCES cpj.cpj_files (cpj_file_id) ON DELETE CASCADE,
    totals_kind              text          NOT NULL,
    inside_sales             numeric(14,2) NOT NULL,
    inside_grand             numeric(14,2) NOT NULL,
    outside_sales            numeric(14,2) NOT NULL,
    outside_grand            numeric(14,2) NOT NULL,
    attendant_inside_sales   numeric(14,2) NOT NULL,
    attendant_inside_grand   numeric(14,2) NOT NULL,
    attendant_outside_sales  numeric(14,2) NOT NULL,
    attendant_outside_grand  numeric(14,2) NOT NULL,
    overall_sales            numeric(14,2) NOT NULL,
    overall_grand            numeric(14,2) NOT NULL,
    CONSTRAINT pk_file_totals      PRIMARY KEY (cpj_file_id, totals_kind),
    CONSTRAINT ck_file_totals_kind CHECK (totals_kind IN ('START', 'END'))
);

-- openCashier + closeCashier for one cashier period on one register.
CREATE TABLE cpj.cashier_sessions (
    cashier_session_id   bigint        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cpj_file_id          bigint        NOT NULL REFERENCES cpj.cpj_files (cpj_file_id) ON DELETE CASCADE,
    register_id          bigint        NOT NULL REFERENCES master.registers (register_id) ON DELETE RESTRICT,     -- posNum
    cashier_version_id   bigint        NOT NULL REFERENCES master.cashier_versions (cashier_version_id) ON DELETE RESTRICT,
    cashier_period       integer       NOT NULL,            -- cashierPeriod
    drawer               integer       NOT NULL,            -- cashier/@drawer
    opened_at            timestamptz   NOT NULL,            -- cOpenTime
    begin_cash           numeric(12,2),                     -- openCashier/beginCash (300.00)
    begin_cash_mop_code  integer,                           -- openCashier/beginCash/@mop
    begin_cash_mop_cat   integer,                           -- openCashier/beginCash/@cat
    closed_at            timestamptz,                       -- closeCashier/cCloseTime
    close_begin_cash     numeric(12,2),                     -- closeCashier/beginCash (0.00 on the samples: differs from the open record)
    close_begin_food_stamp numeric(12,2),                   -- closeCashier/beginFoodStmp
    begin_ticket         bigint,
    end_ticket           bigint,
    ticket_count         integer,
    mws_enabled          boolean,                           -- closeCashier trHeader/mwsEnabled
    CONSTRAINT uq_cashier_sessions        UNIQUE (cpj_file_id, register_id, cashier_period),
    CONSTRAINT ck_cashier_sessions_times  CHECK (closed_at IS NULL OR closed_at >= opened_at),
    CONSTRAINT ck_cashier_sessions_count  CHECK (ticket_count IS NULL OR ticket_count >= 0),
    CONSTRAINT ck_cashier_sessions_open   CHECK ((begin_cash IS NULL) = (begin_cash_mop_code IS NULL)),
    CONSTRAINT ck_cashier_sessions_close  CHECK ((closed_at IS NULL) = (end_ticket IS NULL))
);
CREATE INDEX ix_cashier_sessions_register ON cpj.cashier_sessions (register_id);
CREATE INDEX ix_cashier_sessions_cashier  ON cpj.cashier_sessions (cashier_version_id);

-- trans type="periodClose" (shift and day closes).
CREATE TABLE cpj.period_closes (
    cpj_file_id      bigint       NOT NULL REFERENCES cpj.cpj_files (cpj_file_id) ON DELETE CASCADE,
    period_type      integer      NOT NULL,                 -- periodType (1 = shift, 2 = day)
    period_seq       integer      NOT NULL,
    period_name      text         NOT NULL,                 -- Shift / Day
    closed_at        timestamptz  NOT NULL,                 -- trHeader/date
    unique_id        text         NOT NULL,
    mws_enabled      boolean,
    CONSTRAINT pk_period_closes      PRIMARY KEY (cpj_file_id, period_type, period_seq),
    CONSTRAINT ck_period_closes_name CHECK (btrim(period_name) <> '')
);

-- -------------------------------------------------------------------------------------
-- tickets: every <trans> with a header except journal / openCashier / closeCashier /
-- periodClose: sales, refunds, voids, no-sales, safe drops, payouts, fuel price changes.
-- tr_seq (the ticket number) is NOT unique: a prepay deposit and its rollback copy share it.
-- -------------------------------------------------------------------------------------
CREATE TABLE cpj.tickets (
    ticket_id            bigint        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cpj_file_id          bigint        NOT NULL REFERENCES cpj.cpj_files (cpj_file_id) ON DELETE CASCADE,
    seq_in_file          integer       NOT NULL,            -- position of the <trans> in the file, from 1 (journal trans counted)
    trans_type           text          NOT NULL,            -- trans/@type, as written
    is_suspended         boolean       NOT NULL DEFAULT false,   -- @suspended: parked sale
    is_fuel_prepay       boolean       NOT NULL DEFAULT false,   -- @fuelPrepay: prepay deposit
    is_rollback          boolean       NOT NULL DEFAULT false,   -- @rollback: copy of the deposit
    is_prepay_completion boolean       NOT NULL DEFAULT false,   -- @fuelPrepayCompletion
    is_recalled          boolean       NOT NULL DEFAULT false,   -- @recalled
    register_id          bigint        NOT NULL REFERENCES master.registers (register_id) ON DELETE RESTRICT,  -- trTickNum/posNum, else trHeader/posNum
    tr_seq               bigint,                            -- trTickNum/trSeq
    term_msg_type        text,                              -- termMsgSN/@type
    terminal_no          integer,                           -- termMsgSN/@term
    term_msg_sn          bigint,                            -- termMsgSN
    unique_id            text,                              -- uniqueID
    unique_sn            bigint,                            -- trUniqueSN
    ticket_at            timestamptz   NOT NULL,            -- trHeader/date, with the store's UTC offset
    duration_sec         integer,
    hour_seq             integer,                           -- period level 0 (HOUR)
    shift_seq            integer,                           -- period level 1 (Shift)
    day_seq              integer,                           -- period level 2 (Day)
    till                 integer,
    orig_register_id     bigint        REFERENCES master.registers (register_id) ON DELETE RESTRICT,  -- trOriginalTickNum/posNum
    orig_tr_seq          bigint,                            -- trOriginalTickNum/trSeq (completion -> its prepay)
    recall_trans_type    text,                              -- trRecall/@transType
    recall_register_id   bigint        REFERENCES master.registers (register_id) ON DELETE RESTRICT,  -- trRecall/posNum
    recall_tr_seq        bigint,                            -- trRecall/trSeq
    misc_ref             text,                              -- trMiscRefNum: payout name or safe drop number
    house_account_version_id bigint    REFERENCES master.house_account_versions (house_account_version_id) ON DELETE RESTRICT,
    is_fuel_only_customer boolean      NOT NULL DEFAULT false,   -- empty trFuelOnlyCst present
    currency             text,                              -- trCurrTot/@locale
    total                numeric(12,2),                     -- trCurrTot (negative on refunds)
    total_no_tax         numeric(12,2),                     -- trTotNoTax
    total_tax            numeric(12,2),                     -- trTotTax
    total_with_tax       numeric(12,2),                     -- trTotWTax
    s_totalizer          numeric(12,2),                     -- trSTotalizer
    g_totalizer          numeric(12,2),                     -- trGTotalizer
    recall_amount        numeric(12,2),                     -- recallAmt
    CONSTRAINT uq_tickets_position  UNIQUE (cpj_file_id, seq_in_file),
    CONSTRAINT ck_tickets_seq       CHECK (seq_in_file > 0),
    CONSTRAINT ck_tickets_type      CHECK (trans_type IN ('sale', 'network sale', 'refund sale', 'refund void', 'void',
                                                          'nosale', 'safedrop', 'safeloan', 'payout', 'fuelpriceChange')),
    CONSTRAINT ck_tickets_term      CHECK (term_msg_type IN ('FINANCIAL', 'JOURNAL')),
    CONSTRAINT ck_tickets_duration  CHECK (duration_sec IS NULL OR duration_sec >= 0),
    CONSTRAINT ck_tickets_original  CHECK ((orig_register_id IS NULL) = (orig_tr_seq IS NULL)),
    CONSTRAINT ck_tickets_recall    CHECK ((recall_register_id IS NULL) = (recall_tr_seq IS NULL)
                                       AND (recall_register_id IS NULL) = (recall_trans_type IS NULL)),
    CONSTRAINT ck_tickets_currency  CHECK ((total IS NULL) = (currency IS NULL))
);
CREATE INDEX ix_tickets_file_type     ON cpj.tickets (cpj_file_id, trans_type);
CREATE INDEX ix_tickets_number        ON cpj.tickets (register_id, tr_seq);
CREATE INDEX ix_tickets_orig_register ON cpj.tickets (orig_register_id, orig_tr_seq) WHERE orig_register_id IS NOT NULL;
CREATE INDEX ix_tickets_recall        ON cpj.tickets (recall_register_id, recall_tr_seq) WHERE recall_register_id IS NOT NULL;
CREATE INDEX ix_tickets_house_account ON cpj.tickets (house_account_version_id) WHERE house_account_version_id IS NOT NULL;
CREATE INDEX ix_tickets_unique_id     ON cpj.tickets (unique_id) WHERE unique_id IS NOT NULL;

-- trHeader/cashier and trHeader/originalCashier: the same group of fields twice, so one row each.
CREATE TABLE cpj.ticket_cashiers (
    ticket_id            bigint   NOT NULL REFERENCES cpj.tickets (ticket_id) ON DELETE CASCADE,
    cashier_role         text     NOT NULL,
    cashier_version_id   bigint   NOT NULL REFERENCES master.cashier_versions (cashier_version_id) ON DELETE RESTRICT,
    register_id          bigint   NOT NULL REFERENCES master.registers (register_id) ON DELETE RESTRICT,   -- @posNum
    cashier_period       integer  NOT NULL,                 -- @period
    drawer               integer  NOT NULL,                 -- @drawer
    CONSTRAINT pk_ticket_cashiers      PRIMARY KEY (ticket_id, cashier_role),
    CONSTRAINT ck_ticket_cashiers_role CHECK (cashier_role IN ('CASHIER', 'ORIGINAL'))
);
CREATE INDEX ix_ticket_cashiers_cashier  ON cpj.ticket_cashiers (cashier_version_id);
CREATE INDEX ix_ticket_cashiers_register ON cpj.ticket_cashiers (register_id);

-- trValue/trFstmp (food stamp / EBT part of a ticket)
CREATE TABLE cpj.ticket_food_stamps (
    ticket_id        bigint        PRIMARY KEY REFERENCES cpj.tickets (ticket_id) ON DELETE CASCADE,
    total            numeric(12,2) NOT NULL,                -- trFstmpTot
    tax              numeric(12,2) NOT NULL,                -- trFstmpTax
    change_amount    numeric(12,2) NOT NULL,                -- trFstmpChg
    tendered         numeric(12,2) NOT NULL                 -- trFstmpTnd
);

-- trValue/custDOB: age check. Separate table so access to birth dates can be granted on its
-- own (raw birth date: platform admins only; others see age / age group).
-- @dob is MMDDYY (matched the POS's custAge on all 2,887 sample records); the loader picks
-- the century that gives that age.
CREATE TABLE cpj.ticket_age_checks (
    ticket_id        bigint    PRIMARY KEY REFERENCES cpj.tickets (ticket_id) ON DELETE CASCADE,
    birth_date       date      NOT NULL,
    customer_age     smallint  NOT NULL,                    -- @custAge, worked out by the POS
    min_age          smallint  NOT NULL,                    -- @minAge (21)
    entry_method     text      NOT NULL,                    -- @dobEntryMeth (Manual)
    CONSTRAINT ck_ticket_age_checks_age   CHECK (customer_age BETWEEN 0 AND 130 AND min_age BETWEEN 0 AND 130),
    CONSTRAINT ck_ticket_age_checks_birth CHECK (birth_date >= DATE '1890-01-01'),
    CONSTRAINT ck_ticket_age_checks_entry CHECK (btrim(entry_method) <> '')
);
CREATE INDEX ix_ticket_age_checks_age ON cpj.ticket_age_checks (customer_age);

-- trValue/trTax: per tax per ticket. negTaxAmts (tax handed back on a refund) -> is_refund_tax.
CREATE TABLE cpj.ticket_taxes (
    ticket_id             bigint        NOT NULL REFERENCES cpj.tickets (ticket_id) ON DELETE CASCADE,
    tax_version_id        bigint        NOT NULL REFERENCES master.tax_versions (tax_version_id) ON DELETE RESTRICT,
    is_refund_tax         boolean       NOT NULL,
    taxable_amount        numeric(12,2) NOT NULL,           -- taxAmt
    tax_rate              numeric(7,3)  NOT NULL,           -- taxRate
    tax_amount            numeric(12,2) NOT NULL,           -- taxNet
    tax_attribute         text          NOT NULL,           -- taxAttribute ("0" on every sample; meaning unknown)
    exempt_amount         numeric(12,2),                    -- taxExempt
    exempt_against_amount numeric(12,2),                    -- taxExemptAgainst
    CONSTRAINT pk_ticket_taxes PRIMARY KEY (ticket_id, tax_version_id, is_refund_tax)
);
CREATE INDEX ix_ticket_taxes_tax ON cpj.ticket_taxes (tax_version_id);

-- trScannedCoupons/couponDisc. couponDiscTotal is not stored: it is the sum (loader checks).
CREATE TABLE cpj.ticket_coupons (
    ticket_id        bigint        NOT NULL REFERENCES cpj.tickets (ticket_id) ON DELETE CASCADE,
    coupon_no        smallint      NOT NULL,                -- order inside the ticket, from 1
    discount_amount  numeric(12,2) NOT NULL,
    affects_tax      boolean       NOT NULL,
    entry_method     text          NOT NULL,                -- SCAN_UPCA
    coupon_data      text,                                  -- scanned barcode data as written
    CONSTRAINT pk_ticket_coupons    PRIMARY KEY (ticket_id, coupon_no),
    CONSTRAINT ck_ticket_coupons_no CHECK (coupon_no > 0)
);

-- trExNetProds/trExNetProd: card-network product codes of the ticket
CREATE TABLE cpj.ticket_network_products (
    ticket_id        bigint        NOT NULL REFERENCES cpj.tickets (ticket_id) ON DELETE CASCADE,
    entry_no         smallint      NOT NULL,                -- order inside the ticket, from 1
    product_code     text          NOT NULL,                -- trENPPcode
    item_count       integer       NOT NULL,                -- trENPItemCnt
    amount           numeric(12,2) NOT NULL,                -- trENPAmount (can be negative)
    CONSTRAINT pk_ticket_network_products PRIMARY KEY (ticket_id, entry_no),
    CONSTRAINT ck_ticket_network_products CHECK (entry_no > 0 AND btrim(product_code) <> '')
);

-- trLoyalty/trLoyaltyProgram. The account must belong to the same program (composite FK).
CREATE TABLE cpj.ticket_loyalty (
    ticket_id          bigint        NOT NULL REFERENCES cpj.tickets (ticket_id) ON DELETE CASCADE,
    loyalty_program_id bigint        NOT NULL REFERENCES master.loyalty_programs (loyalty_program_id) ON DELETE RESTRICT,
    loyalty_account_id bigint,                              -- NULL when trloAccount is empty
    auth_reply         text,
    auto_discount      numeric(12,2),                       -- trloAutoDisc
    customer_discount  numeric(12,2),                       -- trloCustDisc
    entry_method       text,                                -- trloEntryMeth
    subtotal           numeric(12,2),                       -- trloSubTotal
    CONSTRAINT pk_ticket_loyalty         PRIMARY KEY (ticket_id, loyalty_program_id),
    CONSTRAINT fk_ticket_loyalty_account FOREIGN KEY (loyalty_program_id, loyalty_account_id)
        REFERENCES master.loyalty_accounts (loyalty_program_id, loyalty_account_id) ON DELETE RESTRICT
);
CREATE INDEX ix_ticket_loyalty_program ON cpj.ticket_loyalty (loyalty_program_id, loyalty_account_id);

-- -------------------------------------------------------------------------------------
-- Payments: trPaylines/trPayline. type="cancel" = money handed back on a refund.
-- -------------------------------------------------------------------------------------
CREATE TABLE cpj.payment_lines (
    payment_line_id          bigint        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ticket_id                bigint        NOT NULL REFERENCES cpj.tickets (ticket_id) ON DELETE CASCADE,
    line_no                  smallint      NOT NULL,        -- order inside trPaylines, from 1
    payline_type             text          NOT NULL,        -- @type
    payment_type_version_id  bigint        NOT NULL REFERENCES master.payment_type_versions (payment_type_version_id) ON DELETE RESTRICT,
    amount                   numeric(12,2) NOT NULL,        -- trpAmt, as written (Change is negative)
    currency                 text,                          -- @locale (US DOLLAR / USD)
    pos_sysid                integer,                       -- @sysid
    is_duplicate             boolean       NOT NULL DEFAULT false,  -- @duplicate
    is_pending               boolean       NOT NULL DEFAULT false,  -- @pending
    original_preauth_amount  numeric(12,2),                 -- @originalPreauth
    coupon_entry_method      text,                          -- trpCouponEntryMethod (COUPON tender; meaning not confirmed)
    CONSTRAINT uq_payment_lines      UNIQUE (ticket_id, line_no),
    CONSTRAINT ck_payment_lines_no   CHECK (line_no > 0),
    CONSTRAINT ck_payment_lines_type CHECK (payline_type IN ('sale', 'cancel')),
    CONSTRAINT ck_payment_lines_coupon_entry CHECK (coupon_entry_method IS NULL OR btrim(coupon_entry_method) <> '')
);
CREATE INDEX ix_payment_lines_type ON cpj.payment_lines (payment_type_version_id);

-- trpCardInfo of a card payment
CREATE TABLE cpj.payment_card_details (
    payment_line_id   bigint       PRIMARY KEY REFERENCES cpj.payment_lines (payment_line_id) ON DELETE CASCADE,
    card_id           bigint       NOT NULL REFERENCES master.cards (card_id) ON DELETE RESTRICT,           -- trpcAccount
    card_type_id      bigint       NOT NULL REFERENCES master.card_types (card_type_id) ON DELETE RESTRICT, -- trpcCCName
    card_name_sysid   integer,                              -- trpcCCName/@sysid
    entry_method      text         NOT NULL,                -- trpcEntryMeth: ContactlessSmartCard, SmartCard, Swipe, Mobile
    auth_code         text,                                 -- trpcAuthCode
    auth_at           timestamptz,                          -- trpcAuthDateTime
    auth_source       text,                                 -- trpcAuthSrc (empty on every sample)
    batch_no          text,                                 -- trpcBatchNr
    acquirer_batch_no text,                                 -- trpcAcquirerBatchNr
    host_id           text,                                 -- trpcHostID (VAPS)
    reference_no      text,                                 -- trpcRefNum
    sequence_no       text,                                 -- trpcSeqNr
    host_ticket       text,                                 -- trpcTicket
    is_above_site     boolean,                              -- trpCardInfo/@isAboveSite
    CONSTRAINT ck_payment_card_details_entry CHECK (btrim(entry_method) <> '')
);
CREATE INDEX ix_payment_card_details_card ON cpj.payment_card_details (card_id);
CREATE INDEX ix_payment_card_details_type ON cpj.payment_card_details (card_type_id);

-- -------------------------------------------------------------------------------------
-- Cashier events kept from the journal (owner's choice): quantity changes, error corrections
-- (with the removed item in cpj.ticket_lines), logins/logouts (with drawer totals), safe drop
-- and pay out drawer openings, price checks. All other journal text is not stored.
-- -------------------------------------------------------------------------------------
CREATE TABLE cpj.cashier_events (
    cashier_event_id   bigint        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cpj_file_id        bigint        NOT NULL REFERENCES cpj.cpj_files (cpj_file_id) ON DELETE CASCADE,
    seq_in_file        integer       NOT NULL,              -- position of the <trans> in the file, same numbering as tickets
    event_type         text          NOT NULL,              -- from trjText/@type
    event_at           timestamptz   NOT NULL,              -- trHeader/date
    register_id        bigint        NOT NULL REFERENCES master.registers (register_id) ON DELETE RESTRICT,  -- trHeader/posNum
    cashier_version_id bigint        REFERENCES master.cashier_versions (cashier_version_id) ON DELETE RESTRICT,
    cashier_entry_mode text,                                -- cashier/@entryMode (keyboardEntry)
    term_msg_sn        bigint,
    unique_id          text,
    event_text         text          NOT NULL,              -- trjText as written
    item_description   text,                                -- CHANGE QTY <item> FROM <a> TO <b>
    from_quantity      numeric(12,3),
    to_quantity        numeric(12,3),
    duration_sec       integer,                             -- trHeader/duration
    hour_seq           integer,                             -- trHeader/period[@level=0]/@seq
    shift_seq          integer,                             -- trHeader/period[@level=1]/@seq
    day_seq            integer,                             -- trHeader/period[@level=2]/@seq
    term_msg_type      text,                                -- trHeader/termMsgSN/@type
    terminal_no        integer,                             -- trHeader/termMsgSN/@term
    cashier_period     integer,                             -- trHeader/cashier/@period
    drawer             integer,                             -- trHeader/cashier/@drawer
    CONSTRAINT uq_cashier_events_position UNIQUE (cpj_file_id, seq_in_file),
    CONSTRAINT ck_cashier_events_type     CHECK (event_type IN ('CHANGE_QTY', 'ERROR_CORRECT', 'LOGIN_LOGOUT',
                                                                'SAFE_DROP', 'PAY_OUT', 'PRICE_CHECK')),
    CONSTRAINT ck_cashier_events_qty      CHECK (event_type = 'CHANGE_QTY'
                                                 OR (item_description IS NULL AND from_quantity IS NULL AND to_quantity IS NULL)),
    CONSTRAINT ck_cashier_events_seq      CHECK (seq_in_file > 0),
    CONSTRAINT ck_cashier_events_term     CHECK (term_msg_type IN ('FINANCIAL', 'JOURNAL')),
    CONSTRAINT ck_cashier_events_duration CHECK (duration_sec IS NULL OR duration_sec >= 0)
);
CREATE INDEX ix_cashier_events_file_type ON cpj.cashier_events (cpj_file_id, event_type);
CREATE INDEX ix_cashier_events_register  ON cpj.cashier_events (register_id);
CREATE INDEX ix_cashier_events_cashier   ON cpj.cashier_events (cashier_version_id) WHERE cashier_version_id IS NOT NULL;

-- trjDrTotals/trjdrTotal of a login/logout event (drawer amount per tender name)
CREATE TABLE cpj.cashier_event_drawer_totals (
    cashier_event_id   bigint        NOT NULL REFERENCES cpj.cashier_events (cashier_event_id) ON DELETE CASCADE,
    tender_name        text          NOT NULL,              -- @name: CASH, DEBIT, CREDIT, FOODSTAMP, PREAUTH, CHECK, ONLINE
    amount             numeric(12,2) NOT NULL,
    CONSTRAINT pk_cashier_event_drawer_totals PRIMARY KEY (cashier_event_id, tender_name),
    CONSTRAINT ck_cashier_event_drawer_totals CHECK (btrim(tender_name) <> '')
);

-- priceCheckCancelItem of a PRICE CHECK event: the item looked up (no department in the file)
CREATE TABLE cpj.cashier_event_price_checks (
    cashier_event_id   bigint        PRIMARY KEY REFERENCES cpj.cashier_events (cashier_event_id) ON DELETE CASCADE,
    item_version_id    bigint        NOT NULL REFERENCES master.item_versions (item_version_id) ON DELETE RESTRICT,  -- trlUPC + trlModifier + trlDesc
    quantity           numeric(12,3) NOT NULL,              -- trlQty
    unit_price         numeric(12,3) NOT NULL,              -- trlUnitPrice
    line_total         numeric(12,2) NOT NULL               -- trlLineTot
);
CREATE INDEX ix_cashier_event_price_checks_item ON cpj.cashier_event_price_checks (item_version_id);

-- -------------------------------------------------------------------------------------
-- ticket_lines: every <trLine>. Belongs to a ticket, or (error corrections) to a cashier
-- event; exactly one of the two. Reports reach lines through tickets, so error-corrected
-- items never mix into sales.
-- trlDesc is stored only when it is NOT the item description (plu lines) or the department
-- name (dept lines): on the samples that leaves fuel lines ("Regular CR #03", "PREPAY CA #01",
-- which carry the pump number of a prepay).
-- -------------------------------------------------------------------------------------
CREATE TABLE cpj.ticket_lines (
    line_id               bigint        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ticket_id             bigint        REFERENCES cpj.tickets (ticket_id) ON DELETE CASCADE,
    cashier_event_id      bigint        REFERENCES cpj.cashier_events (cashier_event_id) ON DELETE CASCADE,
    line_no               smallint      NOT NULL,           -- position inside trLines, from 1
    line_type             text          NOT NULL,           -- @type
    is_duplicate          boolean       NOT NULL DEFAULT false,  -- @duplicate
    is_unsettled          boolean       NOT NULL DEFAULT false,  -- @unsettled
    department_version_id bigint        NOT NULL REFERENCES master.department_versions (department_version_id) ON DELETE RESTRICT,
    category_version_id   bigint        REFERENCES master.category_versions (category_version_id) ON DELETE RESTRICT,
    item_version_id       bigint        REFERENCES master.item_versions (item_version_id) ON DELETE RESTRICT,     -- trlUPC + trlModifier + trlDesc
    upc_entry_method      text,                             -- trlUPCEntry/@type
    network_code          text          NOT NULL,           -- trlNetwCode
    quantity              numeric(12,3) NOT NULL,           -- trlQty (positive)
    sign                  smallint      NOT NULL,           -- trlSign
    sell_unit             numeric(12,3),                    -- trlSellUnit
    unit_price            numeric(12,3) NOT NULL,           -- trlUnitPrice
    line_total            numeric(12,2) NOT NULL,           -- trlLineTot (signed, before mix-and-match)
    price_override        numeric(12,3),                    -- trlPrcOvrd
    discount_type         text,                             -- trlDisc/@type (pct)
    discount_value        numeric(12,2),                    -- trlDisc
    voided_line_no        smallint,                         -- trlVoidLineIx: line_no of the line this void cancels
    pos_description       text,                             -- trlDesc, only when it differs (see above)
    CONSTRAINT uq_ticket_lines_ticket     UNIQUE (ticket_id, line_no),
    CONSTRAINT uq_ticket_lines_event      UNIQUE (cashier_event_id, line_no),
    CONSTRAINT ck_ticket_lines_parent     CHECK (num_nonnulls(ticket_id, cashier_event_id) = 1),
    CONSTRAINT ck_ticket_lines_no         CHECK (line_no > 0),
    CONSTRAINT ck_ticket_lines_type       CHECK (line_type IN ('plu', 'dept', 'postFuel', 'preFuel', 'void plu', 'void dept')),
    CONSTRAINT ck_ticket_lines_item       CHECK ((line_type IN ('plu', 'void plu')) = (item_version_id IS NOT NULL)),
    CONSTRAINT ck_ticket_lines_upc_entry  CHECK ((item_version_id IS NULL) = (upc_entry_method IS NULL)),
    CONSTRAINT ck_ticket_lines_entry      CHECK (upc_entry_method IN ('scanned', 'key', 'manual')),
    CONSTRAINT ck_ticket_lines_void       CHECK ((line_type LIKE 'void %') = (voided_line_no IS NOT NULL)),
    -- in a ticket a void cancels an earlier line of the same ticket; an error-corrected void line
    -- (cashier event) points into the ticket that was being rung, so only > 0 is checked there
    CONSTRAINT ck_ticket_lines_void_order CHECK (voided_line_no IS NULL OR (voided_line_no > 0
                                                 AND (ticket_id IS NULL OR voided_line_no < line_no))),
    CONSTRAINT ck_ticket_lines_qty        CHECK (quantity >= 0),
    CONSTRAINT ck_ticket_lines_sign       CHECK (sign IN (-1, 1)),
    CONSTRAINT ck_ticket_lines_discount   CHECK ((discount_type IS NULL) = (discount_value IS NULL)),
    -- the voided line is in the same ticket (not checked for cashier-event lines: ticket_id is NULL)
    CONSTRAINT fk_ticket_lines_voided     FOREIGN KEY (ticket_id, voided_line_no)
        REFERENCES cpj.ticket_lines (ticket_id, line_no) ON DELETE CASCADE
);
CREATE INDEX ix_ticket_lines_department ON cpj.ticket_lines (department_version_id);
CREATE INDEX ix_ticket_lines_category   ON cpj.ticket_lines (category_version_id) WHERE category_version_id IS NOT NULL;
CREATE INDEX ix_ticket_lines_item       ON cpj.ticket_lines (item_version_id)     WHERE item_version_id IS NOT NULL;

-- trlFlags children. Kinds seen in the 20 sample days; a new kind fails the load until added here.
-- meaning = NULL where Verifone's meaning is not confirmed.
CREATE TABLE cpj.line_flag_types (
    flag_code        text     PRIMARY KEY,
    meaning          text
);
INSERT INTO cpj.line_flag_types (flag_code, meaning) VALUES
    ('trlPLU',        'line rung by item code (PLU/UPC)'),
    ('trlUpdDepCust', NULL),
    ('trlCatCust',    NULL),
    ('trlUpdPluCust', NULL),
    ('trlFuelSale',   'fuel sale line'),
    ('trlFstmp',      'food stamp (EBT) eligible'),
    ('trlSpDisc',     NULL),
    ('trlBdayVerif',  'customer age was checked'),
    ('trlFuelOnly',   NULL),
    ('trlMatch',      'part of a mix-and-match promotion'),
    ('trlNetVoid',    'line was voided later in the same ticket'),
    ('trlNegative',   'negative line; @cause says why (negDept)'),
    ('trlPriceOvrd',  'price was overridden (see ticket_lines.price_override)'),
    ('trlLoyLnDisc',  'loyalty line discount');

CREATE TABLE cpj.line_flags (
    line_id          bigint   NOT NULL REFERENCES cpj.ticket_lines (line_id) ON DELETE CASCADE,
    flag_code        text     NOT NULL REFERENCES cpj.line_flag_types (flag_code) ON DELETE RESTRICT,
    cause            text,                                  -- @cause (only trlNegative has one)
    CONSTRAINT pk_line_flags       PRIMARY KEY (line_id, flag_code),
    CONSTRAINT ck_line_flags_cause CHECK (cause IS NULL OR flag_code = 'trlNegative')
);
CREATE INDEX ix_line_flags_code ON cpj.line_flags (flag_code);

-- trlFuel of a fuel line (postFuel). Prepay lines (preFuel) have no trlFuel element.
CREATE TABLE cpj.line_fuel (
    line_id                      bigint        PRIMARY KEY REFERENCES cpj.ticket_lines (line_id) ON DELETE CASCADE,
    fuel_type                    text          NOT NULL,    -- trlFuel/@type (norm)
    fuel_grade_version_id        bigint        NOT NULL REFERENCES master.fuel_grade_versions (fuel_grade_version_id) ON DELETE RESTRICT,
    fuel_price_level_version_id  bigint        NOT NULL REFERENCES master.fuel_price_level_versions (fuel_price_level_version_id) ON DELETE RESTRICT,
    fuel_service_mode_version_id bigint        NOT NULL REFERENCES master.fuel_service_mode_versions (fuel_service_mode_version_id) ON DELETE RESTRICT,
    fuel_position                smallint      NOT NULL,    -- pump number
    volume                       numeric(12,3) NOT NULL,    -- fuelVolume
    volume_uom                   text          NOT NULL,    -- fuelVolume/@uom (G, USG)
    base_price                   numeric(10,3) NOT NULL,    -- basePrice
    deposit_amount               numeric(12,2),             -- trlFuelDepst
    fuel_seq                     bigint,                    -- trlFuelSeq
    CONSTRAINT ck_line_fuel_values CHECK (fuel_position >= 0 AND volume >= 0 AND btrim(volume_uom) <> '')
);
CREATE INDEX ix_line_fuel_grade   ON cpj.line_fuel (fuel_grade_version_id);
CREATE INDEX ix_line_fuel_level   ON cpj.line_fuel (fuel_price_level_version_id);
CREATE INDEX ix_line_fuel_service ON cpj.line_fuel (fuel_service_mode_version_id);

-- trloPPGDisc: loyalty cents-per-gallon discount on a fuel line (already inside line_total)
CREATE TABLE cpj.line_fuel_discounts (
    line_id            bigint        NOT NULL REFERENCES cpj.ticket_lines (line_id) ON DELETE CASCADE,
    loyalty_program_id bigint        NOT NULL REFERENCES master.loyalty_programs (loyalty_program_id) ON DELETE RESTRICT,  -- @programID
    discount_per_gallon numeric(10,3) NOT NULL,             -- PPGGalDisc
    original_price     numeric(10,3) NOT NULL,              -- PPGOrgPrice
    total_discount     numeric(12,2) NOT NULL,              -- PPGTotDisc
    volume             numeric(12,3) NOT NULL,              -- PPGVol
    -- a fuel line can carry one cents-per-gallon discount per loyalty program (two seen in real files)
    CONSTRAINT pk_line_fuel_discounts PRIMARY KEY (line_id, loyalty_program_id)
);
CREATE INDEX ix_line_fuel_discounts_program ON cpj.line_fuel_discounts (loyalty_program_id);

-- trloLnItemDisc: loyalty discount on an item line
CREATE TABLE cpj.line_loyalty_discounts (
    line_id            bigint        NOT NULL REFERENCES cpj.ticket_lines (line_id) ON DELETE CASCADE,
    loyalty_program_id bigint        NOT NULL REFERENCES master.loyalty_programs (loyalty_program_id) ON DELETE RESTRICT,  -- @programID
    discount_amount    numeric(12,2) NOT NULL,              -- discAmt
    quantity           numeric(12,3) NOT NULL,              -- qty
    tax_credit         numeric(12,2) NOT NULL,              -- taxCred
    CONSTRAINT pk_line_loyalty_discounts PRIMARY KEY (line_id, loyalty_program_id)
);
CREATE INDEX ix_line_loyalty_discounts_program ON cpj.line_loyalty_discounts (loyalty_program_id);

-- trlMixMatches/trlMatchLine: mix-and-match promotion applied to a line
CREATE TABLE cpj.line_promotions (
    line_id              bigint        NOT NULL REFERENCES cpj.ticket_lines (line_id) ON DELETE CASCADE,
    match_no             smallint      NOT NULL,            -- order inside trlMixMatches, from 1
    promotion_version_id bigint        NOT NULL REFERENCES master.promotion_versions (promotion_version_id) ON DELETE RESTRICT,
    mixes                integer       NOT NULL,            -- trlMatchMixes
    match_quantity       numeric(12,3) NOT NULL,            -- trlMatchQuantity
    match_price          numeric(12,2) NOT NULL,            -- trlMatchPrice
    promo_amount         numeric(12,2) NOT NULL,            -- trlPromoAmount
    CONSTRAINT pk_line_promotions    PRIMARY KEY (line_id, match_no),
    CONSTRAINT ck_line_promotions_no CHECK (match_no > 0)
);
CREATE INDEX ix_line_promotions_promotion ON cpj.line_promotions (promotion_version_id);

-- trlTaxes: trlTax + trlRate of the same @sysid
CREATE TABLE cpj.line_taxes (
    line_id          bigint        NOT NULL REFERENCES cpj.ticket_lines (line_id) ON DELETE CASCADE,
    tax_version_id   bigint        NOT NULL REFERENCES master.tax_versions (tax_version_id) ON DELETE RESTRICT,
    tax_amount       numeric(12,2) NOT NULL,                -- trlTax
    tax_rate         numeric(7,3)  NOT NULL,                -- trlRate
    is_reverse       boolean       NOT NULL,                -- trlTax/@reverse
    CONSTRAINT pk_line_taxes PRIMARY KEY (line_id, tax_version_id)
);
CREATE INDEX ix_line_taxes_tax ON cpj.line_taxes (tax_version_id);

-- -------------------------------------------------------------------------------------
-- trans type="fuelpriceChange": header facts + one row per priceChangeInfo.
-- priceChangeInfo carries only sysids (no names), so it points at the codes, not versions.
-- -------------------------------------------------------------------------------------
CREATE TABLE cpj.fuel_price_changes (
    ticket_id        bigint   PRIMARY KEY REFERENCES cpj.tickets (ticket_id) ON DELETE CASCADE,
    changed_by       text     NOT NULL,                     -- priceChangedBy (manager)
    change_origin    text,                                  -- priceChangeOrigin (IP address)
    CONSTRAINT ck_fuel_price_changes_by CHECK (btrim(changed_by) <> '')
);

CREATE TABLE cpj.fuel_price_change_lines (
    ticket_id            bigint        NOT NULL REFERENCES cpj.fuel_price_changes (ticket_id) ON DELETE CASCADE,
    entry_no             smallint      NOT NULL,            -- order inside trPriceChange, from 1
    info_sysid           integer,                           -- priceChangeInfo/@sysid (sometimes absent)
    fuel_grade_id        bigint        NOT NULL REFERENCES master.fuel_grades (fuel_grade_id) ON DELETE RESTRICT,
    fuel_service_mode_id bigint        NOT NULL REFERENCES master.fuel_service_modes (fuel_service_mode_id) ON DELETE RESTRICT,
    fuel_price_level_id  bigint        NOT NULL REFERENCES master.fuel_price_levels (fuel_price_level_id) ON DELETE RESTRICT,
    fuel_tier_id         bigint        NOT NULL REFERENCES master.fuel_tiers (fuel_tier_id) ON DELETE RESTRICT,
    previous_price       numeric(10,3) NOT NULL,
    previous_price_sysid integer,                           -- previousPrice/@sysid
    current_price        numeric(10,3) NOT NULL,
    current_price_sysid  integer,                           -- currentPrice/@sysid
    CONSTRAINT pk_fuel_price_change_lines PRIMARY KEY (ticket_id, entry_no),
    CONSTRAINT ck_fuel_price_change_lines CHECK (entry_no > 0 AND previous_price >= 0 AND current_price >= 0)
);
CREATE INDEX ix_fuel_price_change_lines_grade   ON cpj.fuel_price_change_lines (fuel_grade_id);
CREATE INDEX ix_fuel_price_change_lines_service ON cpj.fuel_price_change_lines (fuel_service_mode_id);
CREATE INDEX ix_fuel_price_change_lines_level   ON cpj.fuel_price_change_lines (fuel_price_level_id);
CREATE INDEX ix_fuel_price_change_lines_tier    ON cpj.fuel_price_change_lines (fuel_tier_id);

-- -------------------------------------------------------------------------------------
-- Checks run inside the load transaction, before commit (e.g. root-level openCashier equals
-- the trans copy, coupon total = sum of coupons, sales counter change = counted tickets,
-- every storeNumber = site_id, no line points at another store's codes).
-- A failed hard check rolls the whole load back; soft checks are kept here with passed = false.
-- -------------------------------------------------------------------------------------
CREATE TABLE cpj.load_checks (
    cpj_file_id      bigint        NOT NULL REFERENCES cpj.cpj_files (cpj_file_id) ON DELETE CASCADE,
    check_name       text          NOT NULL,
    expected         numeric(14,2),
    actual           numeric(14,2),
    passed           boolean       NOT NULL,
    detail           text,
    CONSTRAINT pk_load_checks      PRIMARY KEY (cpj_file_id, check_name),
    CONSTRAINT ck_load_checks_name CHECK (btrim(check_name) <> '')
);


-- #####################################################################################
-- 4. name history views (was V4)
-- #####################################################################################

-- =====================================================================================
-- Name history: for every code, each name it had and the first/last business day a loaded
-- file used that name. Computed from the lines, not stored, so it is always right after a
-- REPLACE or after an older day is loaded late. A version no longer used by any loaded day shows NULL dates.
-- =====================================================================================

-- The business day file of every line (a line hangs off a ticket or a cashier event).
CREATE VIEW cpj.line_files AS
SELECT l.line_id, coalesce(t.cpj_file_id, e.cpj_file_id) AS cpj_file_id
FROM cpj.ticket_lines l
LEFT JOIN cpj.tickets t        ON t.ticket_id = l.ticket_id
LEFT JOIN cpj.cashier_events e ON e.cashier_event_id = l.cashier_event_id;

CREATE VIEW master.department_name_history AS
SELECT d.store_id, d.dept_number, v.department_version_id, v.name, v.dept_type,
       min(f.business_date) AS first_seen, max(f.business_date) AS last_seen
FROM master.departments d
JOIN master.department_versions v ON v.department_id = d.department_id
LEFT JOIN cpj.ticket_lines l      ON l.department_version_id = v.department_version_id
LEFT JOIN cpj.line_files lf       ON lf.line_id = l.line_id
LEFT JOIN cpj.cpj_files f         ON f.cpj_file_id = lf.cpj_file_id
GROUP BY d.store_id, d.dept_number, v.department_version_id, v.name, v.dept_type;

CREATE VIEW master.category_name_history AS
SELECT c.store_id, c.category_number, v.category_version_id, v.name,
       min(f.business_date) AS first_seen, max(f.business_date) AS last_seen
FROM master.categories c
JOIN master.category_versions v ON v.category_id = c.category_id
LEFT JOIN cpj.ticket_lines l    ON l.category_version_id = v.category_version_id
LEFT JOIN cpj.line_files lf     ON lf.line_id = l.line_id
LEFT JOIN cpj.cpj_files f       ON f.cpj_file_id = lf.cpj_file_id
GROUP BY c.store_id, c.category_number, v.category_version_id, v.name;

CREATE VIEW master.item_name_history AS
WITH uses AS (
    SELECT l.item_version_id, lf.cpj_file_id
    FROM cpj.ticket_lines l JOIN cpj.line_files lf ON lf.line_id = l.line_id
    WHERE l.item_version_id IS NOT NULL
    UNION ALL
    SELECT pc.item_version_id, e.cpj_file_id
    FROM cpj.cashier_event_price_checks pc JOIN cpj.cashier_events e ON e.cashier_event_id = pc.cashier_event_id
)
SELECT i.store_id, i.upc, i.upc_modifier, v.item_version_id, v.description,
       min(f.business_date) AS first_seen, max(f.business_date) AS last_seen
FROM master.items i
JOIN master.item_versions v ON v.item_id = i.item_id
LEFT JOIN uses u            ON u.item_version_id = v.item_version_id
LEFT JOIN cpj.cpj_files f   ON f.cpj_file_id = u.cpj_file_id
GROUP BY i.store_id, i.upc, i.upc_modifier, v.item_version_id, v.description;

CREATE VIEW master.cashier_name_history AS
WITH uses AS (
    SELECT tc.cashier_version_id, t.cpj_file_id
    FROM cpj.ticket_cashiers tc JOIN cpj.tickets t ON t.ticket_id = tc.ticket_id
    UNION ALL
    SELECT cashier_version_id, cpj_file_id FROM cpj.cashier_sessions
    UNION ALL
    SELECT cashier_version_id, cpj_file_id FROM cpj.cashier_events WHERE cashier_version_id IS NOT NULL
)
SELECT c.store_id, c.cashier_sysid, v.cashier_version_id, v.name, v.employee_number,
       min(f.business_date) AS first_seen, max(f.business_date) AS last_seen
FROM master.cashiers c
JOIN master.cashier_versions v ON v.cashier_id = c.cashier_id
LEFT JOIN uses u               ON u.cashier_version_id = v.cashier_version_id
LEFT JOIN cpj.cpj_files f      ON f.cpj_file_id = u.cpj_file_id
GROUP BY c.store_id, c.cashier_sysid, v.cashier_version_id, v.name, v.employee_number;

CREATE VIEW master.tax_name_history AS
WITH uses AS (
    SELECT tt.tax_version_id, t.cpj_file_id
    FROM cpj.ticket_taxes tt JOIN cpj.tickets t ON t.ticket_id = tt.ticket_id
    UNION ALL
    SELECT lt.tax_version_id, lf.cpj_file_id
    FROM cpj.line_taxes lt JOIN cpj.line_files lf ON lf.line_id = lt.line_id
)
SELECT x.store_id, x.tax_sysid, v.tax_version_id, v.name,
       min(f.business_date) AS first_seen, max(f.business_date) AS last_seen
FROM master.taxes x
JOIN master.tax_versions v ON v.tax_id = x.tax_id
LEFT JOIN uses u           ON u.tax_version_id = v.tax_version_id
LEFT JOIN cpj.cpj_files f  ON f.cpj_file_id = u.cpj_file_id
GROUP BY x.store_id, x.tax_sysid, v.tax_version_id, v.name;

CREATE VIEW master.payment_type_history AS
SELECT p.store_id, p.mop_code, p.paycode, v.payment_type_version_id, v.mop_category, v.nacs_tender_code, v.nacs_tender_sub,
       min(f.business_date) AS first_seen, max(f.business_date) AS last_seen
FROM master.payment_types p
JOIN master.payment_type_versions v ON v.payment_type_id = p.payment_type_id
LEFT JOIN cpj.payment_lines pl      ON pl.payment_type_version_id = v.payment_type_version_id
LEFT JOIN cpj.tickets t             ON t.ticket_id = pl.ticket_id
LEFT JOIN cpj.cpj_files f           ON f.cpj_file_id = t.cpj_file_id
GROUP BY p.store_id, p.mop_code, p.paycode, v.payment_type_version_id, v.mop_category, v.nacs_tender_code, v.nacs_tender_sub;

CREATE VIEW master.fuel_grade_name_history AS
SELECT g.store_id, g.grade_sysid, v.fuel_grade_version_id, v.name, v.naxml_grade_id,
       min(f.business_date) AS first_seen, max(f.business_date) AS last_seen
FROM master.fuel_grades g
JOIN master.fuel_grade_versions v ON v.fuel_grade_id = g.fuel_grade_id
LEFT JOIN cpj.line_fuel lu        ON lu.fuel_grade_version_id = v.fuel_grade_version_id
LEFT JOIN cpj.line_files lf       ON lf.line_id = lu.line_id
LEFT JOIN cpj.cpj_files f         ON f.cpj_file_id = lf.cpj_file_id
GROUP BY g.store_id, g.grade_sysid, v.fuel_grade_version_id, v.name, v.naxml_grade_id;

CREATE VIEW master.fuel_price_level_name_history AS
SELECT p.store_id, p.level_sysid, v.fuel_price_level_version_id, v.name,
       min(f.business_date) AS first_seen, max(f.business_date) AS last_seen
FROM master.fuel_price_levels p
JOIN master.fuel_price_level_versions v ON v.fuel_price_level_id = p.fuel_price_level_id
LEFT JOIN cpj.line_fuel lu              ON lu.fuel_price_level_version_id = v.fuel_price_level_version_id
LEFT JOIN cpj.line_files lf             ON lf.line_id = lu.line_id
LEFT JOIN cpj.cpj_files f               ON f.cpj_file_id = lf.cpj_file_id
GROUP BY p.store_id, p.level_sysid, v.fuel_price_level_version_id, v.name;

CREATE VIEW master.fuel_service_mode_name_history AS
SELECT m.store_id, m.mode_sysid, v.fuel_service_mode_version_id, v.name,
       min(f.business_date) AS first_seen, max(f.business_date) AS last_seen
FROM master.fuel_service_modes m
JOIN master.fuel_service_mode_versions v ON v.fuel_service_mode_id = m.fuel_service_mode_id
LEFT JOIN cpj.line_fuel lu               ON lu.fuel_service_mode_version_id = v.fuel_service_mode_version_id
LEFT JOIN cpj.line_files lf              ON lf.line_id = lu.line_id
LEFT JOIN cpj.cpj_files f                ON f.cpj_file_id = lf.cpj_file_id
GROUP BY m.store_id, m.mode_sysid, v.fuel_service_mode_version_id, v.name;

CREATE VIEW master.promotion_name_history AS
SELECT p.store_id, p.promotion_code, v.promotion_version_id, v.name, v.promo_type,
       min(f.business_date) AS first_seen, max(f.business_date) AS last_seen
FROM master.promotions p
JOIN master.promotion_versions v ON v.promotion_id = p.promotion_id
LEFT JOIN cpj.line_promotions lp ON lp.promotion_version_id = v.promotion_version_id
LEFT JOIN cpj.line_files lf      ON lf.line_id = lp.line_id
LEFT JOIN cpj.cpj_files f        ON f.cpj_file_id = lf.cpj_file_id
GROUP BY p.store_id, p.promotion_code, v.promotion_version_id, v.name, v.promo_type;

CREATE VIEW master.house_account_name_history AS
SELECT h.store_id, h.account_number, v.house_account_version_id, v.name,
       min(f.business_date) AS first_seen, max(f.business_date) AS last_seen
FROM master.house_accounts h
JOIN master.house_account_versions v ON v.house_account_id = h.house_account_id
LEFT JOIN cpj.tickets t              ON t.house_account_version_id = v.house_account_version_id
LEFT JOIN cpj.cpj_files f            ON f.cpj_file_id = t.cpj_file_id
GROUP BY h.store_id, h.account_number, v.house_account_version_id, v.name;


-- #####################################################################################
-- 5. report views, later phase (was V8)
-- #####################################################################################

-- =====================================================================================
-- LATER, NOT PART OF THE MVP. Drafted and tested on 20 real days (2026-10-17); becomes a
-- migration of verifone-ingestion-service in the reporting phase (renumbered to the next free
-- V<n> then). Several definitions are still PROPOSED: docs/report-definitions.md.
--
-- report: the read-only "report contract" reporting-service serves to the client website.
-- gilbarco_db will get a report schema with the SAME view names and columns, so reporting-service
-- only switches the database by the store's pos_vendor. Business rules live here, once.
-- Definitions and their status (proven / owner-agreed / proposed): docs/report-definitions.md
--
-- R1  A ticket counts as a sale when trans_type is sale / network sale / refund sale and it is
--     not parked (suspended), not a prepay deposit (fuelPrepay) and not the deposit's copy
--     (rollback). A prepay completion counts in full.                       [PROVEN, Tiki]
-- R2  Fuel vs merchandise by department type ('fuel'), not by line type.    [PROVEN, Tiki]
-- R3  trlLineTot is before mix-and-match promotions; net = line total - promotion.  [PROVEN]
-- R4  Lottery = departments a platform admin marked LOTTERY_SALE / LOTTERY_PAYOUT
--     (Tiki: 29 Lotto, 28 Lotto Payout). Item sales (grocery_total) = scanned items +
--     department rings, merchandise only; lottery sales and payouts are separate figures and
--     nothing is subtracted.                                         [OWNER-AGREED 2026-10-09]
-- =====================================================================================

CREATE SCHEMA report;

-- Tickets that count as sales (R1)
CREATE VIEW report.sale_tickets AS
SELECT f.store_id, f.business_date, t.*
FROM cpj.tickets t
JOIN cpj.cpj_files f ON f.cpj_file_id = t.cpj_file_id
WHERE t.trans_type IN ('sale', 'network sale', 'refund sale')
  AND NOT t.is_suspended AND NOT t.is_fuel_prepay AND NOT t.is_rollback;

-- Every sold line of a counted ticket (void lines included: negative, they cancel the line they void).
-- preFuel (prepay deposit) lines are left out: the completion's postFuel line carries the fuel.
CREATE VIEW report.sale_lines AS
SELECT t.store_id, t.business_date, t.cpj_file_id, t.ticket_id, l.line_id,
       t.trans_type, t.tr_seq                                  AS ticket_number,
       r.pos_num                                               AS register,
       t.ticket_at,
       l.line_no, l.line_type,
       CASE WHEN l.line_type IN ('plu', 'void plu')   THEN 'item'
            WHEN l.line_type IN ('dept', 'void dept') THEN 'department'
            ELSE 'fuel' END                                    AS line_kind,
       l.line_type LIKE 'void %'                               AS is_void_line,
       d.dept_number, dv.name                                  AS dept_name, dv.dept_type,
       CASE WHEN dv.dept_type = 'fuel'                THEN 'fuel'
            WHEN d.lottery_class = 'LOTTERY_SALE'     THEN 'lottery_sale'
            WHEN d.lottery_class = 'LOTTERY_PAYOUT'   THEN 'lottery_payout'
            ELSE 'merchandise' END                             AS sales_class,
       i.upc,
       coalesce(iv.description, l.pos_description, dv.name)    AS description,
       l.quantity * l.sign                                     AS quantity,
       l.unit_price,
       l.line_total,
       coalesce(p.promo_amount, 0) * l.sign                    AS promo_amount,
       l.line_total - coalesce(p.promo_amount, 0) * l.sign     AS net_amount,
       coalesce(ld.discount_amount, 0) + coalesce(fd.total_discount, 0) AS discount_amount,  -- already inside line_total
       fg.name                                                 AS fuel_grade,
       pl.name                                                 AS fuel_price_level,
       lf.volume                                               AS fuel_volume,
       lf.fuel_position
FROM report.sale_tickets t
JOIN master.registers r             ON r.register_id = t.register_id
JOIN cpj.ticket_lines l             ON l.ticket_id = t.ticket_id
JOIN master.department_versions dv  ON dv.department_version_id = l.department_version_id
JOIN master.departments d           ON d.department_id = dv.department_id
LEFT JOIN master.item_versions iv   ON iv.item_version_id = l.item_version_id
LEFT JOIN master.items i            ON i.item_id = iv.item_id
LEFT JOIN (SELECT line_id, sum(promo_amount) AS promo_amount FROM cpj.line_promotions GROUP BY line_id) p
                                    ON p.line_id = l.line_id
LEFT JOIN (SELECT line_id, sum(discount_amount) AS discount_amount FROM cpj.line_loyalty_discounts GROUP BY line_id) ld
                                    ON ld.line_id = l.line_id
-- a fuel line can have one cents-per-gallon discount per loyalty program (verifone V5), so sum per line
LEFT JOIN (SELECT line_id, sum(total_discount) AS total_discount FROM cpj.line_fuel_discounts GROUP BY line_id) fd
                                    ON fd.line_id = l.line_id
LEFT JOIN cpj.line_fuel lf           ON lf.line_id = l.line_id
LEFT JOIN master.fuel_grade_versions fg       ON fg.fuel_grade_version_id = lf.fuel_grade_version_id
LEFT JOIN master.fuel_price_level_versions pl ON pl.fuel_price_level_version_id = lf.fuel_price_level_version_id
WHERE l.line_type IN ('plu', 'dept', 'postFuel', 'void plu', 'void dept');

-- One row per transaction for the data browser (all types, counted or not)
CREATE VIEW report.ticket_list AS
SELECT f.store_id, f.business_date, t.ticket_id, t.ticket_at, t.trans_type,
       t.trans_type IN ('sale', 'network sale', 'refund sale')
           AND NOT t.is_suspended AND NOT t.is_fuel_prepay AND NOT t.is_rollback AS counts_as_sale,
       t.is_suspended, t.is_fuel_prepay, t.is_rollback, t.is_prepay_completion, t.is_recalled,
       r.pos_num AS register, t.tr_seq AS ticket_number, cv.name AS cashier,
       t.total, t.total_no_tax, t.total_tax, t.duration_sec, t.misc_ref,
       (SELECT count(*) FROM cpj.ticket_lines l WHERE l.ticket_id = t.ticket_id)    AS line_count,
       (SELECT string_agg(DISTINCT pt.paycode, ', ')
          FROM cpj.payment_lines pl
          JOIN master.payment_type_versions pv ON pv.payment_type_version_id = pl.payment_type_version_id
          JOIN master.payment_types pt         ON pt.payment_type_id = pv.payment_type_id
         WHERE pl.ticket_id = t.ticket_id AND pt.paycode <> 'Change')                AS paid_with,
       EXISTS (SELECT 1 FROM cpj.ticket_age_checks a WHERE a.ticket_id = t.ticket_id) AS has_age_check
FROM cpj.tickets t
JOIN cpj.cpj_files f            ON f.cpj_file_id = t.cpj_file_id
JOIN master.registers r         ON r.register_id = t.register_id
LEFT JOIN cpj.ticket_cashiers c ON c.ticket_id = t.ticket_id AND c.cashier_role = 'CASHIER'
LEFT JOIN master.cashier_versions cv ON cv.cashier_version_id = c.cashier_version_id;

-- Payments of counted tickets per type, as recorded; Change is merged into CASH.
CREATE VIEW report.payments_daily AS
SELECT t.store_id, t.business_date,
       CASE WHEN pt.paycode = 'Change' THEN 'CASH' ELSE pt.paycode END                       AS payment_type,
       count(*) FILTER (WHERE pt.paycode <> 'Change' AND t.trans_type <> 'refund sale')      AS payments,
       coalesce(sum(CASE WHEN pl.payline_type = 'cancel' THEN -pl.amount ELSE pl.amount END)
                FILTER (WHERE t.trans_type <> 'refund sale'), 0)                             AS sales_amount,
       count(*) FILTER (WHERE t.trans_type = 'refund sale')                                  AS refunds,
       coalesce(sum(pl.amount) FILTER (WHERE t.trans_type = 'refund sale'), 0)               AS refunded_amount,
       sum(CASE WHEN pl.payline_type = 'cancel' THEN -pl.amount ELSE pl.amount END)          AS net_amount
FROM report.sale_tickets t
JOIN cpj.payment_lines pl            ON pl.ticket_id = t.ticket_id
JOIN master.payment_type_versions pv ON pv.payment_type_version_id = pl.payment_type_version_id
JOIN master.payment_types pt         ON pt.payment_type_id = pv.payment_type_id
GROUP BY t.store_id, t.business_date, 3;

-- Safe drops, safe loans and payouts, one row each
CREATE VIEW report.cash_movements AS
SELECT f.store_id, f.business_date, t.ticket_id,
       CASE t.trans_type WHEN 'safedrop' THEN 'safe_drop' WHEN 'payout' THEN 'payout' ELSE 'safe_loan' END AS kind,
       t.ticket_at AS occurred_at, r.pos_num AS register, t.tr_seq AS ticket_number,
       t.total AS amount, t.misc_ref AS reference,                       -- payout name or drop number
       (SELECT string_agg(DISTINCT pt.paycode, ', ')
          FROM cpj.payment_lines pl
          JOIN master.payment_type_versions pv ON pv.payment_type_version_id = pl.payment_type_version_id
          JOIN master.payment_types pt         ON pt.payment_type_id = pv.payment_type_id
         WHERE pl.ticket_id = t.ticket_id) AS paid_with
FROM cpj.tickets t
JOIN cpj.cpj_files f    ON f.cpj_file_id = t.cpj_file_id
JOIN master.registers r ON r.register_id = t.register_id
WHERE t.trans_type IN ('safedrop', 'payout', 'safeloan');

-- The owner's five suspicious kinds, every occurrence: no_sale, void_line, void, refund, refund_void
CREATE VIEW report.suspicious_events AS
SELECT f.store_id, f.business_date, t.ticket_id, NULL::bigint AS line_id, 'no_sale'::text AS kind,
       t.ticket_at AS occurred_at, r.pos_num AS register, t.tr_seq AS ticket_number,
       NULL::numeric AS amount, NULL::text AS item, t.duration_sec
FROM cpj.tickets t JOIN cpj.cpj_files f ON f.cpj_file_id = t.cpj_file_id JOIN master.registers r ON r.register_id = t.register_id
WHERE t.trans_type = 'nosale'
UNION ALL
SELECT s.store_id, s.business_date, s.ticket_id, s.line_id, 'void_line', s.ticket_at, s.register, s.ticket_number,
       -s.line_total, s.description, NULL
FROM report.sale_lines s WHERE s.is_void_line
UNION ALL
SELECT f.store_id, f.business_date, t.ticket_id, NULL, 'void', t.ticket_at, r.pos_num, t.tr_seq,
       t.total_no_tax, NULL, t.duration_sec
FROM cpj.tickets t JOIN cpj.cpj_files f ON f.cpj_file_id = t.cpj_file_id JOIN master.registers r ON r.register_id = t.register_id
WHERE t.trans_type = 'void'
UNION ALL
SELECT s.store_id, s.business_date, s.ticket_id, s.line_id, 'refund', s.ticket_at, s.register, s.ticket_number,
       -s.line_total, s.description, NULL
FROM report.sale_lines s WHERE NOT s.is_void_line AND s.line_total < 0 AND s.sales_class <> 'lottery_payout'
UNION ALL
SELECT f.store_id, f.business_date, t.ticket_id, NULL, 'refund_void', t.ticket_at, r.pos_num, t.tr_seq,
       t.total, NULL, t.duration_sec
FROM cpj.tickets t JOIN cpj.cpj_files f ON f.cpj_file_id = t.cpj_file_id JOIN master.registers r ON r.register_id = t.register_id
WHERE t.trans_type = 'refund void';

-- DAILY_REPORTS of Table_Schema.mmd (columns CPJ can give) plus checks; one row per store per day
CREATE VIEW report.daily_summary AS
WITH f AS (
    SELECT f.store_id, f.business_date, count(*) AS files,
           min(f.opened_at) AS period_opened, max(f.closed_at) AS period_closed,
           sum(e.overall_sales - s.overall_sales) AS sales_counter
    FROM cpj.cpj_files f
    JOIN cpj.file_totals s ON s.cpj_file_id = f.cpj_file_id AND s.totals_kind = 'START'
    JOIN cpj.file_totals e ON e.cpj_file_id = f.cpj_file_id AND e.totals_kind = 'END'
    GROUP BY f.store_id, f.business_date
), t AS (
    SELECT store_id, business_date, count(*) AS customers, sum(total) AS ticket_totals,
           sum(total_no_tax) AS ticket_totals_no_tax, sum(total_tax) AS tax
    FROM report.sale_tickets GROUP BY store_id, business_date
), s AS (
    SELECT store_id, business_date,
           sum(net_amount)   FILTER (WHERE sales_class = 'merchandise')                     AS grocery_total,
           sum(net_amount)   FILTER (WHERE sales_class = 'lottery_sale')                    AS lottery_sales,
           -sum(net_amount)  FILTER (WHERE sales_class = 'lottery_payout')                  AS lottery_payouts,
           sum(line_total)   FILTER (WHERE sales_class = 'fuel')                            AS fuel_sales,
           sum(fuel_volume)  FILTER (WHERE line_type = 'postFuel')                          AS fuel_volume,
           sum(promo_amount)                                                                AS promotions,
           count(*)          FILTER (WHERE is_void_line)                                    AS line_void_count,
           -sum(line_total)  FILTER (WHERE is_void_line)                                    AS line_void_amount,
           count(*)          FILTER (WHERE NOT is_void_line AND line_total < 0 AND sales_class <> 'lottery_payout') AS refund_count,
           -sum(line_total)  FILTER (WHERE NOT is_void_line AND line_total < 0 AND sales_class <> 'lottery_payout') AS refund_amount
    FROM report.sale_lines GROUP BY store_id, business_date
), c AS (
    SELECT m.store_id, m.business_date,
           count(*) FILTER (WHERE m.kind = 'safe_drop') AS safe_drops,
           coalesce(sum(pl.amount) FILTER (WHERE m.kind = 'safe_drop' AND pt.paycode = 'CASH'), 0)  AS cash_deposit,
           coalesce(sum(pl.amount) FILTER (WHERE m.kind = 'safe_drop' AND pt.paycode = 'CHECK'), 0) AS check_deposit
    FROM report.cash_movements m
    JOIN cpj.payment_lines pl            ON pl.ticket_id = m.ticket_id
    JOIN master.payment_type_versions pv ON pv.payment_type_version_id = pl.payment_type_version_id
    JOIN master.payment_types pt         ON pt.payment_type_id = pv.payment_type_id
    GROUP BY m.store_id, m.business_date
), x AS (
    SELECT f.store_id, f.business_date,
           count(*) FILTER (WHERE t.trans_type = 'nosale')                          AS no_sale_count,
           count(*) FILTER (WHERE t.trans_type = 'void')                            AS void_count,
           coalesce(sum(t.total_no_tax) FILTER (WHERE t.trans_type = 'void'), 0)    AS void_amount,
           count(*) FILTER (WHERE t.trans_type = 'refund void')                     AS refund_void_count
    FROM cpj.tickets t JOIN cpj.cpj_files f ON f.cpj_file_id = t.cpj_file_id
    GROUP BY f.store_id, f.business_date
)
SELECT f.store_id, f.business_date, f.files, f.period_opened, f.period_closed,
       coalesce(s.grocery_total, 0)    AS grocery_total,
       coalesce(s.fuel_volume, 0)      AS volume,
       c.cash_deposit, c.check_deposit,  -- NULL when the day has no safe drop
       coalesce(x.no_sale_count, 0)    AS no_sale_count,
       coalesce(s.line_void_count, 0)  AS line_void_count,
       coalesce(s.line_void_amount, 0) AS line_void_amount,
       coalesce(x.void_count, 0)       AS void_count,
       coalesce(x.void_amount, 0)      AS void_amount,
       coalesce(s.refund_count, 0)     AS refund_count,
       coalesce(s.refund_amount, 0)    AS refund_amount,
       coalesce(x.refund_void_count, 0) AS refund_void_count,
       coalesce(s.lottery_sales, 0)    AS lottery_sales,
       coalesce(s.lottery_payouts, 0)  AS lottery_payouts,
       coalesce(s.fuel_sales, 0)       AS fuel_sales,
       coalesce(s.promotions, 0)       AS promotions,
       coalesce(t.customers, 0)        AS customers,
       coalesce(t.tax, 0)              AS tax,
       coalesce(t.ticket_totals, 0)    AS total_sales_with_tax,
       coalesce(t.ticket_totals_no_tax, 0) AS total_sales_before_tax,
       coalesce(c.safe_drops, 0)       AS safe_drops,
       f.sales_counter,
       f.sales_counter = coalesce(t.ticket_totals, 0) AS counter_ties   -- PROVEN check: POS counter change = counted tickets
FROM f
LEFT JOIN t USING (store_id, business_date)
LEFT JOIN s USING (store_id, business_date)
LEFT JOIN c USING (store_id, business_date)
LEFT JOIN x USING (store_id, business_date);

-- Department sales per day / per month (MONTHLY_REPORTS of Table_Schema.mmd).
-- gross = positive sold lines (lottery payouts' negative lines count as their gross);
-- refund and void are shown as positive amounts; net = gross - refund - void - promotion
-- = sum(line_total) - promotions. discount is informational: already inside the line totals.
CREATE VIEW report.department_daily AS
SELECT store_id, business_date, dept_number,
       (array_agg(dept_name ORDER BY ticket_at DESC, line_id DESC))[1]                 AS dept_name,
       sales_class,
       sum(quantity)                                                                    AS units,
       coalesce(sum(line_total) FILTER (WHERE NOT is_void_line AND (line_total >= 0 OR sales_class = 'lottery_payout')), 0) AS gross,
       coalesce(sum(promo_amount), 0)                                                   AS promotion,
       coalesce(sum(discount_amount), 0)                                                AS discount,
       coalesce(-sum(line_total) FILTER (WHERE NOT is_void_line AND line_total < 0 AND sales_class <> 'lottery_payout'), 0) AS refund,
       coalesce(-sum(line_total) FILTER (WHERE is_void_line), 0)                        AS void_amount,
       sum(net_amount)                                                                  AS net_sales,
       sum(fuel_volume)                                                                 AS fuel_volume
FROM report.sale_lines
GROUP BY store_id, business_date, dept_number, sales_class;

CREATE VIEW report.department_monthly AS
SELECT store_id,
       extract(year FROM business_date)::int  AS report_year,
       extract(month FROM business_date)::int AS report_month,
       dept_number,
       (array_agg(dept_name ORDER BY ticket_at DESC, line_id DESC))[1]                 AS dept_name,
       sales_class,
       sum(quantity)                                                                    AS units,
       coalesce(sum(line_total) FILTER (WHERE NOT is_void_line AND (line_total >= 0 OR sales_class = 'lottery_payout')), 0) AS gross,
       coalesce(sum(promo_amount), 0)                                                   AS promotion,
       coalesce(sum(discount_amount), 0)                                                AS discount,
       coalesce(-sum(line_total) FILTER (WHERE NOT is_void_line AND line_total < 0 AND sales_class <> 'lottery_payout'), 0) AS refund,
       coalesce(-sum(line_total) FILTER (WHERE is_void_line), 0)                        AS void_amount,
       sum(net_amount)                                                                  AS net_sales,
       sum(fuel_volume)                                                                 AS fuel_volume
FROM report.sale_lines
GROUP BY store_id, 2, 3, dept_number, sales_class;

-- Items sold per day: scanned items and department rings (non-fuel), with their sales class
CREATE VIEW report.items_daily AS
SELECT store_id, business_date, line_kind AS item_type, upc, dept_number,
       (array_agg(description ORDER BY ticket_at DESC, line_id DESC))[1] AS description,
       (array_agg(dept_name ORDER BY ticket_at DESC, line_id DESC))[1]   AS dept_name,
       sales_class,
       sum(quantity)            AS units,
       sum(line_total)          AS gross,
       sum(promo_amount)        AS promotion,
       sum(net_amount)          AS net_sales,
       count(DISTINCT ticket_id) AS tickets
FROM report.sale_lines
WHERE line_kind IN ('item', 'department')
GROUP BY store_id, business_date, line_kind, upc, dept_number, sales_class;

-- Lottery per day / per month (LOTTERY_SALES_REPORTS_MONTHLY: CPJ has no online/scratch split
-- and no commission). Both amounts positive, nothing netted.
CREATE VIEW report.lottery_daily AS
SELECT store_id, business_date,
       count(*)                     FILTER (WHERE sales_class = 'lottery_sale' AND NOT is_void_line)   AS sales_lines,
       coalesce(sum(net_amount)     FILTER (WHERE sales_class = 'lottery_sale'), 0)                     AS sales_amount,
       count(*)                     FILTER (WHERE sales_class = 'lottery_payout' AND NOT is_void_line) AS payouts,
       coalesce(-sum(net_amount)    FILTER (WHERE sales_class = 'lottery_payout'), 0)                   AS payout_amount
FROM report.sale_lines
WHERE sales_class IN ('lottery_sale', 'lottery_payout')
GROUP BY store_id, business_date;

CREATE VIEW report.lottery_monthly AS
SELECT store_id,
       extract(year FROM business_date)::int AS report_year, extract(month FROM business_date)::int AS report_month,
       sum(sales_lines) AS sales_lines, sum(sales_amount) AS sales_amount,
       sum(payouts) AS payouts, sum(payout_amount) AS payout_amount
FROM report.lottery_daily
GROUP BY store_id, 2, 3;

-- Fuel per day / per month by grade and price level (GAS_SALES_REPORTS_MONTHLY + details: CPJ has
-- no fuel cost or card fees, so no profit columns)
CREATE VIEW report.fuel_daily AS
SELECT store_id, business_date, fuel_grade, fuel_price_level,
       sum(fuel_volume)                         AS volume,
       sum(line_total)                          AS amount,
       count(*) FILTER (WHERE fuel_volume > 0)  AS fuel_sales,
       sum(discount_amount)                     AS ppg_discount
FROM report.sale_lines
WHERE line_type = 'postFuel'
GROUP BY store_id, business_date, fuel_grade, fuel_price_level;

CREATE VIEW report.fuel_monthly AS
SELECT store_id,
       extract(year FROM business_date)::int AS report_year, extract(month FROM business_date)::int AS report_month,
       fuel_grade, fuel_price_level,
       sum(volume) AS volume, sum(amount) AS amount, sum(fuel_sales) AS fuel_sales, sum(ppg_discount) AS ppg_discount
FROM report.fuel_daily
GROUP BY store_id, 2, 3, fuel_grade, fuel_price_level;

-- Who buys what by age group: counted tickets that carry an age check. Only age-restricted sales
-- have an age, and on the samples every age was typed by hand (see docs/report-definitions.md).
-- Birth dates are NOT in this view.
CREATE VIEW report.age_group_sales_daily AS
SELECT s.store_id, s.business_date,
       CASE WHEN a.customer_age < 21 THEN 'under 21'
            WHEN a.customer_age < 25 THEN '21-24'
            WHEN a.customer_age < 35 THEN '25-34'
            WHEN a.customer_age < 45 THEN '35-44'
            WHEN a.customer_age < 55 THEN '45-54'
            WHEN a.customer_age < 65 THEN '55-64'
            ELSE '65+' END                  AS age_group,
       a.entry_method,
       s.sales_class, s.dept_number,
       (array_agg(s.dept_name ORDER BY s.ticket_at DESC, s.line_id DESC))[1] AS dept_name,
       count(DISTINCT s.ticket_id)          AS tickets,
       sum(s.quantity)                      AS units,
       sum(s.net_amount)                    AS net_sales
FROM report.sale_lines s
JOIN cpj.ticket_age_checks a ON a.ticket_id = s.ticket_id
GROUP BY s.store_id, s.business_date, 3, a.entry_method, s.sales_class, s.dept_number;

-- Days loaded per store (for date pickers)
CREATE VIEW report.loaded_days AS
SELECT f.store_id, f.business_date, count(*) AS files, max(f.loaded_at) AS loaded_at
FROM cpj.cpj_files f
GROUP BY f.store_id, f.business_date;


-- #####################################################################################
-- 6. reporting_svc grants, skipped where the role is absent (was V9)
-- #####################################################################################

-- =====================================================================================
-- LATER, NOT PART OF THE MVP: applied together with the report views (renumbered then).
-- reporting-service reads verifone_db through its own role, reporting_svc: SELECT only, and only the
-- schemas it serves (report, cpj, master). Never ingest, never INSERT/UPDATE/DELETE.
-- Default privileges make tables/views created by later migrations readable too.
-- reporting_svc is created by infra/postgres/01_roles_and_databases.sql; skipped where absent.
-- =====================================================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'reporting_svc') THEN
        GRANT USAGE ON SCHEMA report, cpj, master TO reporting_svc;
        GRANT SELECT ON ALL TABLES IN SCHEMA report, cpj, master TO reporting_svc;
        ALTER DEFAULT PRIVILEGES IN SCHEMA report, cpj, master GRANT SELECT ON TABLES TO reporting_svc;
    END IF;
END $$;


-- #####################################################################################
-- 7. lottery class fixed by the department name (was V10)
-- #####################################################################################

-- Owner decision (2026-10-22): the lottery class is fixed by the department name, not chosen by an admin.
--   name contains LOTTO or LOTTERY                                  -> lottery
--   ... and the name contains PAYOUT, or the department is negative -> LOTTERY_PAYOUT, else LOTTERY_SALE
-- Tiki Hut: 29 Lotto (norm) = LOTTERY_SALE, 28 Lotto Payout (neg) = LOTTERY_PAYOUT.
-- The class follows the department's newest name (the version inserted last).

CREATE FUNCTION master.lottery_class_for(dept_name text, dept_type text) RETURNS text
    LANGUAGE sql IMMUTABLE STRICT AS $$
    SELECT CASE
        WHEN upper(dept_name) !~ '(LOTTO|LOTTERY)' THEN NULL
        WHEN upper(dept_name) ~ 'PAYOUT' OR dept_type = 'neg' THEN 'LOTTERY_PAYOUT'
        ELSE 'LOTTERY_SALE'
    END
$$;

CREATE FUNCTION master.departments_set_lottery_class() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE master.departments
    SET lottery_class = master.lottery_class_for(NEW.name, NEW.dept_type)
    WHERE department_id = NEW.department_id;
    RETURN NULL;
END $$;

CREATE TRIGGER tr_department_versions_lottery_class AFTER INSERT ON master.department_versions
    FOR EACH ROW EXECUTE FUNCTION master.departments_set_lottery_class();
