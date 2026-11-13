-- =====================================================================================
-- pjr: Gilbarco Passport POS journal (NAXML-POSJournal 3.4). One PJR file = exactly one event.
-- Ported from HOR-XML/pjr-platform (V1, V2, V4), keyed by master.stores.store_id.
-- Loading stores what the file says; the counting rules live in generated columns here and in the
-- report views (V3). Not stored on purpose: card numbers, approval / authorisation codes, loyalty IDs.
-- A day's rows hang off pjr_file, so REPLACE deletes pjr_file rows of the day and the cascade does the rest.
-- =====================================================================================
CREATE SCHEMA pjr;

-- One row per loaded PJR file
CREATE TABLE pjr.pjr_file (
    file_id               bigserial     PRIMARY KEY,
    store_id              bigint        NOT NULL REFERENCES master.stores (store_id) ON DELETE RESTRICT,
    store_code            text          NOT NULL,
    s3_object_id          bigint        REFERENCES ingest.s3_objects (s3_object_id) ON DELETE CASCADE,
    file_name             text          NOT NULL,          -- PJR3402609230004181533189.xml
    sha256                char(64)      NOT NULL,
    size_bytes            integer       NOT NULL,
    written_at            timestamp,                       -- yymmdd hhmmss in the name: when Passport wrote it (NOT the business day)
    file_counter          bigint,                          -- running counter at the end of the name (all file types share it)
    store_location        text,                            -- TransmissionHeader/StoreLocationID
    vendor_version        text,                            -- TransmissionHeader/VendorModelVersion
    business_date         date          NOT NULL,          -- the event's BusinessDate
    shift_begin           timestamp,                       -- JournalHeader/BeginDate + BeginTime: start of the current shift
    loaded_at             timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT uq_pjr_file UNIQUE (store_id, file_name),
    CONSTRAINT ck_pjr_file_sha CHECK (sha256 ~ '^[0-9a-f]{64}$')
);
CREATE INDEX ix_pjr_file_day ON pjr.pjr_file (store_id, business_date);
CREATE INDEX ix_pjr_file_s3_object ON pjr.pjr_file (s3_object_id);

-- One row per event (= per file)
CREATE TABLE pjr.event (
    file_id               bigint        PRIMARY KEY REFERENCES pjr.pjr_file (file_id) ON DELETE CASCADE,
    store_id              bigint        NOT NULL REFERENCES master.stores (store_id) ON DELETE RESTRICT,
    store_code            text          NOT NULL,
    business_date         date          NOT NULL,
    event_type            text          NOT NULL,          -- SaleEvent | RefundEvent | VoidEvent | OtherEvent | FinancialEvent
    detail_kind           text          NOT NULL,          -- sale | refund | void | no_sale | register_open | register_close | safe_drop | payout | pay_in
    transaction_id        bigint,                          -- NOT unique over time (register 2 wraps 9999 -> 1)
    register_id           integer,
    register_group        text          GENERATED ALWAYS AS (
                              CASE WHEN register_id BETWEEN 10001 AND 10999 THEN 'pump'
                                   WHEN register_id IS NOT NULL THEN 'inside' END) STORED,
    cashier_id            text,
    till_id               text,
    started_at            timestamp,                       -- EventStartDate + EventStartTime (store-local)
    ended_at              timestamp,
    receipt_at            timestamp,
    is_outside            boolean       NOT NULL DEFAULT false,  -- OutsideSalesFlag: paid at the pump
    is_suspended          boolean       NOT NULL DEFAULT false,  -- SuspendFlag: prepay deposit, NOT a sale
    is_training           boolean       NOT NULL DEFAULT false,
    is_offline            boolean       NOT NULL DEFAULT false,
    counts_as_sale        boolean       GENERATED ALWAYS AS (
                              event_type IN ('SaleEvent', 'RefundEvent') AND NOT is_suspended) STORED,
    linked_register_id    integer,                         -- LinkedTransactionInfo: completion -> its deposit
    linked_transaction_id bigint,
    link_reason           text,                            -- fuelPrepay
    loyalty_program       text,                            -- Extension/LoyaltyInfo/LoyaltyProgramName (the ID is not stored)
    total_gross           numeric(12,2),
    total_net             numeric(12,2),
    total_tax             numeric(12,2),
    total_grand           numeric(12,2),
    grand_direction       text,                            -- Collected | Refunded
    detail_amount         numeric(12,2),                   -- DropAmount | PayOutDetail/DetailAmount | CashInDrawer
    payee                 text,                            -- PayOutDetail/AccountInfo/AccountName (vendor paid)
    alert_code            text,                            -- AlertDetail/AlertCode (CashDrawerOpen)
    reason                text,                            -- VoidReason | RefundReason | PayOutReason
    CONSTRAINT ck_pjr_event_type CHECK (event_type IN ('SaleEvent', 'RefundEvent', 'VoidEvent', 'OtherEvent', 'FinancialEvent'))
);
CREATE INDEX ix_pjr_event_day ON pjr.event (store_id, business_date, detail_kind);
CREATE INDEX ix_pjr_event_txn ON pjr.event (store_id, register_id, transaction_id);

-- One row per item, department ring, fuel or prepay line.
-- line_no is the position among all TransactionLines of the event (shared with tender_line and event_tax).
CREATE TABLE pjr.event_line (
    file_id               bigint        NOT NULL REFERENCES pjr.event (file_id) ON DELETE CASCADE,
    line_no               smallint      NOT NULL,
    store_id              bigint        NOT NULL REFERENCES master.stores (store_id) ON DELETE RESTRICT,
    store_code            text          NOT NULL,
    business_date         date          NOT NULL,
    status                text          NOT NULL,          -- normal | cancel
    line_type             text          NOT NULL,          -- ItemLine | MerchandiseCodeLine | FuelLine | FuelPrepayLine
    dept_number           integer,                         -- MerchandiseCode
    pos_code              text,
    pos_code_format       text,
    pos_code_modifier     text,
    description           text,
    entry_method          text,
    quantity              numeric(12,3),                   -- SalesQuantity (gallons on fuel lines; negative on returns)
    selling_units         numeric(12,3),
    actual_price          numeric(12,3),
    regular_price         numeric(12,3),
    sales_amount          numeric(12,2) NOT NULL DEFAULT 0,  -- AFTER promotions; 0 on cancel lines
    promotion_amount      numeric(12,2) NOT NULL DEFAULT 0,  -- sum of Promotion/PromotionAmount (negative)
    discount_amount       numeric(12,2) NOT NULL DEFAULT 0,  -- sum of Discount/DiscountAmount (negative)
    promotion_reasons     text,
    gross_amount          numeric(12,2) GENERATED ALWAYS AS (sales_amount - promotion_amount - discount_amount) STORED,
    void_value            numeric(12,2) GENERATED ALWAYS AS (
                              CASE WHEN status = 'cancel' AND line_type IN ('ItemLine', 'FuelLine')
                                   THEN round(regular_price * quantity, 2) END) STORED,
    tax_level             text,
    fuel_grade_id         text,                            -- 001 SUPER, 002 PLUS, 003 REGULAR
    fuel_position         smallint,
    price_tier            text,
    service_level         text,
    PRIMARY KEY (file_id, line_no)
);
CREATE INDEX ix_pjr_line_day  ON pjr.event_line (store_id, business_date, line_type);
CREATE INDEX ix_pjr_line_dept ON pjr.event_line (store_id, dept_number, business_date);
CREATE INDEX ix_pjr_line_code ON pjr.event_line (pos_code) WHERE pos_code IS NOT NULL;

-- One row per TenderInfo line
CREATE TABLE pjr.tender_line (
    file_id               bigint        NOT NULL REFERENCES pjr.event (file_id) ON DELETE CASCADE,
    line_no               smallint      NOT NULL,
    status                text          NOT NULL,
    tender_code           text          NOT NULL,          -- cash, creditCards, debitCards, outsideCredit, outsideDebit, generic
    tender_sub            text,                            -- generic/generic = EBT Food, generic/store = HOUSE
    amount                numeric(12,2) NOT NULL,          -- change and money back are negative
    is_change             boolean       NOT NULL DEFAULT false,
    provider              text,
    authorized_amount     numeric(12,2),
    PRIMARY KEY (file_id, line_no)
);

-- One row per TransactionTax line
CREATE TABLE pjr.event_tax (
    file_id               bigint        NOT NULL REFERENCES pjr.event (file_id) ON DELETE CASCADE,
    line_no               smallint      NOT NULL,
    status                text          NOT NULL,
    tax_level             text          NOT NULL,          -- TaxLevelID (99, 101, 102)
    taxable_amount        numeric(12,2),
    tax_collected         numeric(12,2),
    taxable_refunded      numeric(12,2),
    tax_refunded          numeric(12,2),
    tax_exempt            numeric(12,2),
    tax_forgiven_sales    numeric(12,2),
    PRIMARY KEY (file_id, line_no)
);

-- Files that could not be loaded. The day's load is rolled back (nothing half-loaded), so the failure is
-- written here in its own transaction, for diagnosis.
CREATE TABLE pjr.load_failure (
    failure_id            bigserial     PRIMARY KEY,
    store_id              bigint        REFERENCES master.stores (store_id) ON DELETE CASCADE,
    store_code            text,
    fetch_request_id      bigint        REFERENCES ingest.fetch_requests (fetch_request_id) ON DELETE SET NULL,
    file_name             text          NOT NULL,
    sha256                char(64),
    error                 text          NOT NULL,
    failed_at             timestamptz   NOT NULL DEFAULT now()
);
CREATE INDEX ix_pjr_load_failure_store ON pjr.load_failure (store_id, failed_at);
