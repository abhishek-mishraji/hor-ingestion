-- =====================================================================================
-- V7: lottery figures a platform admin or HOR staff member enters for one store-day.
-- Same migration in both vendor databases (verifone_db V4, gilbarco_db V7): keep them identical.
--
-- The client-dashboard Daily report shows the lottery figures from the POS (lottery departments) and
-- the rows the register does not record (settlement, adjustment, credits, commission) as 0. Admin and
-- staff may correct any of them for any day:
--
--   report.lottery_day_entry          one row per store-day; a NULL value = "use the POS figure",
--                                     a number = the entered figure (it replaces the POS one)
--   report.lottery_day_entry_change   every change, one row per changed field (new_value NULL = reset)
--
-- Written only by the reporting services (PUT /api/reports/daily-lottery); the ingestion services
-- never touch these tables. version is raised on every save, so two people saving the same day at once
-- cannot overwrite each other (the second save is rejected with 409).
-- Amounts are dollars with 2 decimals (signs as shown on the page: cashes, credits, adjustment and
-- commission are entered as positive amounts); tickets are counts.
-- =====================================================================================
CREATE TABLE report.lottery_day_entry (
    store_id         bigint        NOT NULL REFERENCES master.stores (store_id) ON DELETE RESTRICT,
    business_date    date          NOT NULL,
    lottery_sales    numeric(12,2),           -- register without a scratch-off / online split
    lottery_tickets  integer,
    scratch_sales    numeric(12,2),
    scratch_tickets  integer,
    online_sales     numeric(12,2),
    online_tickets   integer,
    online_cashes    numeric(12,2),
    scratch_cashes   numeric(12,2),           -- POS: all winnings paid (the register cannot split them)
    cashes_tickets   integer,
    settlement       numeric(12,2),
    adjustment       numeric(12,2),
    online_credit    numeric(12,2),
    scratch_credit   numeric(12,2),
    commission       numeric(12,2),
    version          integer       NOT NULL DEFAULT 1,
    updated_by       bigint        NOT NULL,  -- hor-auth account id
    updated_by_name  text          NOT NULL,  -- name in the token at the time of the save
    updated_at       timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_lottery_day_entry PRIMARY KEY (store_id, business_date),
    CONSTRAINT ck_lottery_day_entry_version CHECK (version > 0),
    CONSTRAINT ck_lottery_day_entry_tickets CHECK (
        coalesce(lottery_tickets, 0) >= 0 AND coalesce(scratch_tickets, 0) >= 0
        AND coalesce(online_tickets, 0) >= 0 AND coalesce(cashes_tickets, 0) >= 0)
);

CREATE TABLE report.lottery_day_entry_change (
    change_id        bigint        GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    store_id         bigint        NOT NULL,
    business_date    date          NOT NULL,
    field            text          NOT NULL,
    old_value        numeric(12,2),           -- NULL: was the POS figure
    new_value        numeric(12,2),           -- NULL: reset to the POS figure
    changed_by       bigint        NOT NULL,
    changed_by_name  text          NOT NULL,
    changed_at       timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT fk_lottery_day_entry_change FOREIGN KEY (store_id, business_date)
        REFERENCES report.lottery_day_entry (store_id, business_date) ON DELETE CASCADE,
    CONSTRAINT ck_lottery_day_entry_change_field CHECK (field IN (
        'lottery_sales', 'lottery_tickets', 'scratch_sales', 'scratch_tickets', 'online_sales',
        'online_tickets', 'online_cashes', 'scratch_cashes', 'cashes_tickets', 'settlement',
        'adjustment', 'online_credit', 'scratch_credit', 'commission'))
);
CREATE INDEX ix_lottery_day_entry_change ON report.lottery_day_entry_change (store_id, business_date, changed_at DESC);
