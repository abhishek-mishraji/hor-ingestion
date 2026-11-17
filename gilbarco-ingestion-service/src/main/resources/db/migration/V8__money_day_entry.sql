-- =====================================================================================
-- V8: Money Out figures a platform admin or HOR staff member enters for one store-day.
-- Same migration in both vendor databases (verifone_db V5, gilbarco_db V8): keep them identical.
--
-- Daily report "Safe Deposits": Total deposits = cash deposit + check deposit, then the credit card batch.
--   cash_deposit   POS default: safe drops
--   check_deposit  not in the POS: 0 until entered
--   card_batch     POS default: credit, debit, pump and mobile card payments
--
--   report.money_day_entry          one row per store-day; a NULL value = "use the POS figure",
--                                   a number = the entered figure (it replaces the POS one)
--   report.money_day_entry_change   every change, one row per changed field (new_value NULL = reset)
--
-- Written only by the reporting services (PUT /api/reports/daily-money); the ingestion services never
-- touch these tables. version is raised on every save (a stale save is rejected with 409).
-- More Money Out rows can be added later as new nullable columns (and to the field CHECK list).
-- =====================================================================================
CREATE TABLE report.money_day_entry (
    store_id         bigint        NOT NULL REFERENCES master.stores (store_id) ON DELETE RESTRICT,
    business_date    date          NOT NULL,
    cash_deposit     numeric(12,2),
    check_deposit    numeric(12,2),
    card_batch       numeric(12,2),
    version          integer       NOT NULL DEFAULT 1,
    updated_by       bigint        NOT NULL,  -- hor-auth account id
    updated_by_name  text          NOT NULL,  -- name in the token at the time of the save
    updated_at       timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_money_day_entry PRIMARY KEY (store_id, business_date),
    CONSTRAINT ck_money_day_entry_version CHECK (version > 0)
);

CREATE TABLE report.money_day_entry_change (
    change_id        bigint        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    store_id         bigint        NOT NULL,
    business_date    date          NOT NULL,
    field            text          NOT NULL,
    old_value        numeric(12,2),           -- NULL: was the POS figure
    new_value        numeric(12,2),           -- NULL: reset to the POS figure
    changed_by       bigint        NOT NULL,
    changed_by_name  text          NOT NULL,
    changed_at       timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT fk_money_day_entry_change FOREIGN KEY (store_id, business_date)
        REFERENCES report.money_day_entry (store_id, business_date) ON DELETE CASCADE,
    CONSTRAINT ck_money_day_entry_change_field CHECK (field IN ('cash_deposit', 'check_deposit', 'card_batch'))
);
CREATE INDEX ix_money_day_entry_change ON report.money_day_entry_change (store_id, business_date, changed_at DESC);
