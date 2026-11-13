-- =====================================================================================
-- V6: mcm, the store-level Merchandise Code Movement (NAXML-MovementReport 3.4, PrimaryReportPeriod 2).
-- Why it is loaded at all:
--   1. Department names. A scanned PJR item carries only the department number; MCM names every department
--      the day sold (MerchandiseCodeDescription). The loader upserts master.departments.pos_name from it with
--      the same rule as PJR (V5): an admin-set name and class are never changed.
--   2. Proof. MCM is Passport's own department total of the day. A complete PJR load rebuilds it to the cent:
--        MCM SalesAmount                      = PJR gross_sales + refund        (rpt_department_daily)
--        -(MCM PromotionAmount + DiscountAmount) = PJR promotions
--        -MCM RefundAmount                    = PJR refund
--      Held on every department of every Wolf day 2026-10-10..24 (14 days). A difference means a PJR file is
--      missing or a counting rule broke; report.rpt_mcm_day_check shows it the day it happens.
-- Not stored on purpose: the per-register file (period 98, a breakdown of the same close) and the raw XML (S3).
-- About 22 rows per store per day. REPLACE deletes the day's rows; deleting the zip record cascades.
-- =====================================================================================
CREATE SCHEMA mcm;

CREATE TABLE mcm.department_day (
    store_id                     bigint         NOT NULL REFERENCES master.stores (store_id) ON DELETE RESTRICT,
    store_code                   text           NOT NULL,
    business_date                date           NOT NULL,          -- MovementHeader/BusinessDate
    dept_number                  integer        NOT NULL,          -- MerchandiseCode
    dept_name                    text,                             -- MerchandiseCodeDescription, as the file gives it
    sales_amount                 numeric(12,2)  NOT NULL,          -- before promotions; refunded lines included (positive)
    sales_quantity               numeric(12,3)  NOT NULL,          -- fuel: lines, not gallons
    promotion_amount             numeric(12,2)  NOT NULL,          -- negative
    promotion_count              integer        NOT NULL,
    discount_amount              numeric(12,2)  NOT NULL,          -- negative (Wolf: fuel discount)
    discount_count               integer        NOT NULL,
    refund_amount                numeric(12,2)  NOT NULL,          -- negative
    refund_count                 integer        NOT NULL,
    transaction_count            integer        NOT NULL,
    open_dept_sales_amount       numeric(12,2)  NOT NULL,          -- OpenDepartmentSalesAmount (rung, not scanned)
    open_dept_transaction_count  integer        NOT NULL,
    s3_object_id                 bigint         NOT NULL REFERENCES ingest.s3_objects (s3_object_id) ON DELETE CASCADE,
    file_name                    text           NOT NULL,          -- MCM3402610250014071598846.xml
    sha256                       char(64)       NOT NULL,
    period_begin                 timestamp      NOT NULL,          -- MovementHeader BeginDate + BeginTime (store-local)
    period_end                   timestamp      NOT NULL,          -- MovementHeader EndDate + EndTime
    loaded_at                    timestamptz    NOT NULL DEFAULT now(),
    PRIMARY KEY (store_id, business_date, dept_number),
    CONSTRAINT ck_mcm_department_day_sha    CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_mcm_department_day_period CHECK (period_end >= period_begin)
);
CREATE INDEX ix_mcm_department_day_s3_object ON mcm.department_day (s3_object_id);

-- -------------------------------------------------------------------------------------
-- MCM vs PJR per department and day (the identities in the header). PJR side = rpt_department_daily summed
-- over its sales classes. A department only one side has shows the other side as 0.
-- -------------------------------------------------------------------------------------
CREATE VIEW report.rpt_mcm_department_check AS
WITH p AS (
    SELECT store_id, store_code, business_date, dept_number,
           max(dept_name)                   AS dept_name,
           sum(gross_sales + refund)        AS sales,
           sum(promotions)                  AS promotions,
           sum(refund)                      AS refund
    FROM report.rpt_department_daily
    WHERE dept_number IS NOT NULL
    GROUP BY store_id, store_code, business_date, dept_number
), m AS (
    SELECT store_id, store_code, business_date, dept_number, dept_name,
           sales_amount                         AS sales,
           -(promotion_amount + discount_amount) AS promotions,
           -refund_amount                       AS refund
    FROM mcm.department_day
)
SELECT coalesce(m.store_id, p.store_id)                 AS store_id,
       coalesce(m.store_code, p.store_code)             AS store_code,
       coalesce(m.business_date, p.business_date)       AS business_date,
       coalesce(m.dept_number, p.dept_number)           AS dept_number,
       coalesce(m.dept_name, p.dept_name)               AS dept_name,
       coalesce(m.sales, 0)                             AS mcm_sales,
       coalesce(p.sales, 0)                             AS pjr_sales,
       coalesce(p.sales, 0) - coalesce(m.sales, 0)      AS sales_diff,
       coalesce(m.promotions, 0)                        AS mcm_promotions,
       coalesce(p.promotions, 0)                        AS pjr_promotions,
       coalesce(p.promotions, 0) - coalesce(m.promotions, 0) AS promotions_diff,
       coalesce(m.refund, 0)                            AS mcm_refund,
       coalesce(p.refund, 0)                            AS pjr_refund,
       coalesce(p.refund, 0) - coalesce(m.refund, 0)    AS refund_diff,
       coalesce(p.sales, 0) = coalesce(m.sales, 0)
         AND coalesce(p.promotions, 0) = coalesce(m.promotions, 0)
         AND coalesce(p.refund, 0) = coalesce(m.refund, 0) AS matches
FROM m
FULL JOIN p ON p.store_id = m.store_id AND p.business_date = m.business_date AND p.dept_number = m.dept_number;

-- -------------------------------------------------------------------------------------
-- One row per store and day that has MCM or PJR loaded.
-- status: match | mismatch (a department differs) | mcm_missing (PJR loaded, no MCM) | pjr_missing (the reverse)
-- -------------------------------------------------------------------------------------
CREATE VIEW report.rpt_mcm_day_check AS
WITH days AS (
    SELECT DISTINCT store_id, store_code, business_date, true AS mcm, false AS pjr FROM mcm.department_day
    UNION ALL
    SELECT DISTINCT store_id, store_code, business_date, false, true FROM pjr.pjr_file
), d AS (
    SELECT store_id, max(store_code) AS store_code, business_date, bool_or(mcm) AS mcm_loaded, bool_or(pjr) AS pjr_loaded
    FROM days GROUP BY store_id, business_date
), c AS (
    SELECT store_id, business_date,
           sum(mcm_sales)                       AS mcm_sales,
           sum(pjr_sales)                       AS pjr_sales,
           sum(mcm_promotions)                  AS mcm_promotions,
           sum(pjr_promotions)                  AS pjr_promotions,
           count(*)                             AS departments,
           count(*) FILTER (WHERE NOT matches)  AS departments_off
    FROM report.rpt_mcm_department_check
    GROUP BY store_id, business_date
)
SELECT d.store_id,
       d.store_code,
       d.business_date,
       d.mcm_loaded,
       d.pjr_loaded,
       coalesce(c.mcm_sales, 0)                         AS mcm_sales,
       coalesce(c.pjr_sales, 0)                         AS pjr_sales,
       coalesce(c.pjr_sales, 0) - coalesce(c.mcm_sales, 0) AS sales_diff,
       coalesce(c.mcm_promotions, 0)                    AS mcm_promotions,
       coalesce(c.pjr_promotions, 0)                    AS pjr_promotions,
       coalesce(c.departments, 0)                       AS departments,
       coalesce(c.departments_off, 0)                   AS departments_off,
       CASE WHEN NOT d.pjr_loaded THEN 'pjr_missing'
            WHEN NOT d.mcm_loaded THEN 'mcm_missing'
            WHEN coalesce(c.departments_off, 0) = 0 THEN 'match'
            ELSE 'mismatch' END                         AS status
FROM d
LEFT JOIN c ON c.store_id = d.store_id AND c.business_date = d.business_date;
