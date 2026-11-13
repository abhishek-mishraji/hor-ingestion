-- =====================================================================================
-- report: PJR reporting views, reconciled to the cent against Wolf's StoreClose PDFs (22-28 Sep 2026).
-- Ported from HOR-XML/pjr-platform at its final state (V3 + V5 + V6 + V7 + V8), keyed by store_id.
-- The counting rules live here, once; dashboards and other services only read these views.
--   * Sales = events with counts_as_sale (SaleEvent / RefundEvent, not a suspended prepay deposit;
--     VoidEvents never). Sold lines = status 'normal' only (cancel lines are voids or prepay reversals).
--   * net_amount = SalesAmount (after promotions); gross_amount = before promotions and discounts;
--     promo_amount = gross - net.
--   * Lottery: master.departments.sales_class (lottery_sale / lottery_payout); payouts never subtracted.
--   * Item sales = scanned items (ItemLine) + department rings (MerchandiseCodeLine), merchandise only.
--   * Void lines are counted per transaction on sale/refund transactions; cancel lines inside prepay
--     deposits and inside voided transactions are separate kinds, never added to the main count.
-- =====================================================================================
CREATE SCHEMA report;

-- Every sold line on a counted transaction
CREATE VIEW report.v_sold_line AS
SELECT e.store_id,
       e.store_code,
       e.business_date,
       e.file_id,
       e.transaction_id                          AS ticket_number,
       e.event_type,
       e.register_id                             AS register,
       e.register_group,
       e.cashier_id                              AS cashier,
       e.started_at,
       e.ended_at                                AS sold_at,
       l.line_no,
       l.line_type,
       CASE l.line_type WHEN 'ItemLine' THEN 'item' WHEN 'MerchandiseCodeLine' THEN 'department' ELSE 'fuel' END
                                                 AS line_kind,
       l.dept_number,
       CASE WHEN l.line_type = 'FuelLine' THEN 'Fuel' ELSE d.dept_name END AS dept_name,
       CASE WHEN l.line_type = 'FuelLine' THEN 'fuel' ELSE coalesce(d.sales_class, 'merchandise') END AS sales_class,
       l.pos_code                                AS upc,
       l.pos_code_format,
       l.description,
       l.entry_method,
       l.quantity,
       l.actual_price                            AS unit_price,
       l.regular_price,
       l.gross_amount,
       l.gross_amount - l.sales_amount           AS promo_amount,
       l.sales_amount                            AS net_amount,
       l.promotion_reasons,
       l.fuel_grade_id,
       CASE WHEN l.line_type = 'FuelLine' THEN l.description END AS fuel_grade,
       l.price_tier                              AS fuel_price_level,
       CASE WHEN l.line_type = 'FuelLine' THEN l.quantity END    AS fuel_volume,
       l.fuel_position
FROM pjr.event e
JOIN pjr.event_line l USING (file_id)
LEFT JOIN master.departments d ON d.store_id = e.store_id AND d.dept_number = l.dept_number
WHERE e.counts_as_sale
  AND l.status = 'normal'
  AND l.line_type IN ('ItemLine', 'MerchandiseCodeLine', 'FuelLine');

-- Every voided line (status 'cancel' on an item, department ring or fuel line), wherever it sits.
-- where_found: sale (a counted sale or refund), prepay_deposit (suspended deposit), voided_transaction (VoidEvent).
CREATE VIEW report.v_void_line AS
SELECT e.store_id,
       e.store_code,
       e.business_date,
       e.file_id,
       e.transaction_id                          AS ticket_number,
       e.event_type,
       e.register_id                             AS register,
       e.cashier_id                              AS cashier,
       e.started_at,
       e.ended_at,
       CASE WHEN e.counts_as_sale THEN 'sale'
            WHEN e.is_suspended THEN 'prepay_deposit'
            WHEN e.event_type = 'VoidEvent' THEN 'voided_transaction'
            ELSE 'other' END                     AS where_found,
       l.line_no,
       l.line_type,
       l.dept_number,
       d.dept_name,
       l.pos_code                                AS upc,
       l.description,
       l.quantity,
       l.regular_price,
       l.void_value                              -- regular price x quantity; NULL for a department ring (no price)
FROM pjr.event e
JOIN pjr.event_line l USING (file_id)
LEFT JOIN master.departments d ON d.store_id = e.store_id AND d.dept_number = l.dept_number
WHERE l.status = 'cancel'
  AND l.line_type IN ('ItemLine', 'MerchandiseCodeLine', 'FuelLine');

-- Item sales per day: scanned items + department rings, merchandise only (lottery and fuel are separate)
CREATE VIEW report.rpt_items_sold_daily AS
SELECT store_id,
       store_code,
       business_date,
       CASE WHEN line_kind = 'item' THEN 'scanned' ELSE 'department ring' END AS item_type,
       upc,
       max(description)                         AS description,
       dept_number,
       max(dept_name)                           AS dept_name,
       sales_class,
       sum(quantity)                            AS units_sold,
       sum(gross_amount)                        AS gross_sales,
       sum(promo_amount)                        AS promotions,
       sum(net_amount)                          AS net_sales,
       count(DISTINCT file_id)                  AS tickets
FROM report.v_sold_line
WHERE line_kind IN ('item', 'department')
  AND sales_class = 'merchandise'
GROUP BY store_id, store_code, business_date, 4, upc, dept_number, sales_class;

-- Department sales per day (items, department rings and fuel; sales_class tells them apart).
-- gross_sales = the StoreClose department table's gross + refund columns.
CREATE VIEW report.rpt_department_daily AS
SELECT store_id,
       store_code,
       business_date,
       dept_number,
       max(dept_name)                           AS dept_name,
       sales_class,
       sum(quantity)                            AS units_sold,
       sum(gross_amount)                        AS gross_sales,
       sum(promo_amount)                        AS promotions,
       sum(net_amount)                          AS net_sales,
       sum(fuel_volume)                         AS fuel_gallons,
       count(*)                                 AS lines
FROM report.v_sold_line
GROUP BY store_id, store_code, business_date, dept_number, sales_class;

-- Lottery per day: sales and payouts side by side, both positive amounts, nothing netted
CREATE VIEW report.rpt_lottery_daily AS
SELECT store_id,
       store_code,
       business_date,
       count(*)          FILTER (WHERE sales_class = 'lottery_sale')    AS sales_lines,
       sum(quantity)     FILTER (WHERE sales_class = 'lottery_sale')    AS sales_units,
       coalesce(sum(net_amount) FILTER (WHERE sales_class = 'lottery_sale'), 0)     AS sales_amount,
       count(*)          FILTER (WHERE sales_class = 'lottery_payout')  AS payouts,
       coalesce(-sum(net_amount) FILTER (WHERE sales_class = 'lottery_payout'), 0)  AS payout_amount
FROM report.v_sold_line
WHERE sales_class IN ('lottery_sale', 'lottery_payout')
GROUP BY store_id, store_code, business_date;

-- Lottery payouts per day (shown on their own, never subtracted)
CREATE VIEW report.rpt_lottery_payout_daily AS
SELECT store_id,
       store_code,
       business_date,
       count(*)                                 AS payouts,
       -sum(net_amount)                         AS payout_amount      -- positive: money paid to winners
FROM report.v_sold_line
WHERE sales_class = 'lottery_payout'
GROUP BY store_id, store_code, business_date;

-- Fuel per day by grade and price tier.
-- amount = gross (StoreClose fuel lines); fuel_discount = fuel promotions (StoreClose "Fuel Discounts").
CREATE VIEW report.rpt_fuel_daily AS
SELECT store_id,
       store_code,
       business_date,
       fuel_grade_id,
       fuel_grade,
       fuel_price_level,
       sum(fuel_volume)                         AS gallons,
       sum(gross_amount)                        AS amount,
       sum(net_amount)                          AS net_amount,
       count(*) FILTER (WHERE fuel_volume > 0)  AS fuel_sales,
       sum(promo_amount)                        AS fuel_discount
FROM report.v_sold_line
WHERE line_kind = 'fuel'
GROUP BY store_id, store_code, business_date, fuel_grade_id, fuel_grade, fuel_price_level;

-- Payments per day per payment type (code + subcode; StoreClose names). net_amount = the StoreClose tender line.
-- payments = every payment line except cash change, as the StoreClose "Store Tender Reading" counts them.
CREATE VIEW report.rpt_payment_daily AS
SELECT e.store_id,
       e.store_code,
       e.business_date,
       CASE t.tender_code || '/' || coalesce(t.tender_sub, '')
            WHEN 'cash/generic'          THEN 'Cash'
            WHEN 'creditCards/generic'   THEN 'Credit'
            WHEN 'debitCards/generic'    THEN 'Debit'
            WHEN 'generic/generic'       THEN 'EBT Food'
            WHEN 'generic/store'         THEN 'HOUSE'
            WHEN 'outsideCredit/generic' THEN 'Crind CREDIT'
            WHEN 'outsideDebit/generic'  THEN 'Crind DEBIT'
            ELSE t.tender_code || '/' || coalesce(t.tender_sub, '') END                      AS payment_type,
       t.tender_code,
       t.tender_sub,
       count(*) FILTER (WHERE e.event_type = 'SaleEvent' AND NOT (t.is_change AND t.tender_code = 'cash')) AS payments,
       coalesce(sum(t.amount) FILTER (WHERE e.event_type = 'SaleEvent'), 0)                   AS sales_amount,
       count(*) FILTER (WHERE e.event_type = 'RefundEvent')                                   AS refunds,
       coalesce(-sum(t.amount) FILTER (WHERE e.event_type = 'RefundEvent'), 0)                AS refunded_amount,
       sum(t.amount)                                                                          AS net_amount
FROM pjr.event e
JOIN pjr.tender_line t USING (file_id)
WHERE e.counts_as_sale AND t.status = 'normal'
GROUP BY e.store_id, e.store_code, e.business_date, 4, t.tender_code, t.tender_sub;

-- Cash movements, one row each. safe_loan = register opened with its starting cash (StoreClose "Safe Loans").
-- pay_in = cash paid into the drawer (seen from October 2026; not yet reconciled to a StoreClose PDF).
-- Amounts are positive (a payout is money out of the drawer).
CREATE VIEW report.rpt_cash_movement AS
SELECT e.store_id,
       e.store_code,
       e.business_date,
       CASE e.detail_kind WHEN 'register_open' THEN 'safe_loan' ELSE e.detail_kind END AS kind,
       e.ended_at                               AS occurred_at,
       e.register_id                            AS register,
       e.cashier_id                             AS cashier,
       e.till_id                                AS till,
       e.transaction_id                         AS ticket_number,
       e.file_id,
       abs(e.detail_amount)                     AS amount,
       e.payee                                  AS reference
FROM pjr.event e
WHERE e.detail_kind IN ('safe_drop', 'payout', 'pay_in', 'register_open');

-- Suspicious activity: every occurrence. Void lines are one row per transaction (lines = voided items).
CREATE VIEW report.rpt_suspicious_event AS
SELECT e.store_id, e.store_code, e.business_date, e.ended_at AS occurred_at, 'no_sale'::text AS kind,
       e.register_id AS register, e.cashier_id AS cashier, e.transaction_id AS ticket_number, e.file_id,
       NULL::numeric AS amount, NULL::text AS item, NULL::numeric AS quantity,
       extract(epoch FROM e.ended_at - e.started_at)::integer AS duration_sec,
       'drawer opened without a sale'::text AS detail,
       e.started_at, e.ended_at,
       NULL::integer AS lines
FROM pjr.event e
WHERE e.detail_kind = 'no_sale'
UNION ALL
SELECT v.store_id, v.store_code, v.business_date, max(v.ended_at),
       CASE v.where_found WHEN 'sale' THEN 'void_line'
                          WHEN 'prepay_deposit' THEN 'void_line_in_prepay_deposit'
                          ELSE 'void_line_in_voided_transaction' END,
       max(v.register), max(v.cashier), max(v.ticket_number), v.file_id,
       sum(v.void_value),
       string_agg(coalesce(v.description, 'department ' || v.dept_number), ', ' ORDER BY v.line_no),
       sum(v.quantity),
       extract(epoch FROM max(v.ended_at) - max(v.started_at))::integer,
       count(*) || CASE WHEN count(*) = 1 THEN ' item voided' ELSE ' items voided' END
         || CASE WHEN count(*) FILTER (WHERE v.void_value IS NULL) > 0
                 THEN ' (' || count(*) FILTER (WHERE v.void_value IS NULL)
                      || ' department ring(s) with no amount in the file)' ELSE '' END
         || ', valued at regular price'
         || CASE v.where_found WHEN 'sale' THEN '; transaction then paid ' || coalesce(max(e.total_grand)::text, '?')
                               WHEN 'prepay_deposit' THEN '; inside a prepay deposit'
                               ELSE '; the whole transaction was then voided' END,
       max(v.started_at), max(v.ended_at),
       count(*)::integer
FROM report.v_void_line v
JOIN pjr.event e USING (file_id)
WHERE v.where_found <> 'other'
GROUP BY v.store_id, v.store_code, v.business_date, v.file_id, v.where_found
UNION ALL
SELECT e.store_id, e.store_code, e.business_date, e.ended_at, 'void',
       e.register_id, e.cashier_id, e.transaction_id, e.file_id,
       e.total_grand, i.items, NULL,
       extract(epoch FROM e.ended_at - e.started_at)::integer,
       'whole transaction cancelled; total ' || coalesce(e.total_grand::text, '0'),
       e.started_at, e.ended_at,
       NULL
FROM pjr.event e
LEFT JOIN LATERAL (SELECT string_agg(coalesce(l.description, CASE WHEN l.line_type = 'FuelPrepayLine'
                                       THEN 'prepay pump ' || l.fuel_position || ' ' || l.sales_amount END),
                                     ', ' ORDER BY l.line_no) AS items
                   FROM pjr.event_line l WHERE l.file_id = e.file_id) i ON true
WHERE e.detail_kind = 'void'
UNION ALL
SELECT s.store_id, s.store_code, s.business_date, s.sold_at, 'refund',
       s.register, s.cashier, s.ticket_number, s.file_id,
       s.gross_amount, s.description, s.quantity,
       extract(epoch FROM s.sold_at - s.started_at)::integer,
       coalesce('money back: ' || m.back, 'no money handed back on this transaction'),
       s.started_at, s.sold_at,
       NULL
FROM report.v_sold_line s
LEFT JOIN LATERAL (SELECT string_agg(t.tender_code || ' ' || t.amount, ', ') AS back FROM pjr.tender_line t
                   WHERE t.file_id = s.file_id AND t.status = 'normal' AND t.amount < 0) m ON true
WHERE s.gross_amount < 0 AND s.sales_class <> 'lottery_payout';

-- Suspicious activity: daily totals per kind (void_line = transactions with voided items)
CREATE VIEW report.rpt_suspicious_daily AS
SELECT store_id, store_code, business_date, kind,
       CASE WHEN kind = 'void_line' THEN count(*) ELSE sum(coalesce(lines, 1)) END AS occurrences,
       sum(amount) AS amount
FROM report.rpt_suspicious_event
GROUP BY store_id, store_code, business_date, kind;

-- Voids per day, every way of counting them. void_lines = transactions with voided items on counted
-- transactions; deposit / voided-transaction void lines are separate columns (items), never added in.
CREATE VIEW report.rpt_void_daily AS
WITH d AS (
    SELECT DISTINCT store_id, store_code, business_date FROM pjr.pjr_file
), vl AS (
    SELECT store_id, store_code, business_date,
           count(DISTINCT file_id) FILTER (WHERE where_found = 'sale')                         AS void_lines,
           sum(quantity)           FILTER (WHERE where_found = 'sale')                         AS voided_units,
           count(DISTINCT file_id) FILTER (WHERE where_found = 'sale')                         AS void_line_transactions,
           coalesce(sum(void_value) FILTER (WHERE where_found = 'sale'), 0)                    AS void_line_amount,
           count(*)                FILTER (WHERE where_found = 'sale' AND void_value IS NULL)  AS void_ring_lines,
           count(*)                FILTER (WHERE where_found = 'prepay_deposit')               AS deposit_void_lines,
           coalesce(sum(void_value) FILTER (WHERE where_found = 'prepay_deposit'), 0)          AS deposit_void_line_amount,
           count(*)                FILTER (WHERE where_found = 'voided_transaction')           AS voided_txn_void_lines,
           coalesce(sum(void_value) FILTER (WHERE where_found = 'voided_transaction'), 0)       AS voided_txn_void_line_amount
    FROM report.v_void_line
    GROUP BY store_id, store_code, business_date
), vt AS (
    SELECT store_id, store_code, business_date,
           count(*)                                  AS voids,
           coalesce(sum(total_grand), 0)             AS void_amount          -- = StoreClose "Transaction Voids"
    FROM pjr.event WHERE detail_kind = 'void'
    GROUP BY store_id, store_code, business_date
)
SELECT d.store_id,
       d.store_code,
       d.business_date,
       coalesce(vl.void_lines, 0)                    AS void_lines,
       coalesce(vl.voided_units, 0)                  AS voided_units,
       coalesce(vl.void_line_transactions, 0)        AS void_line_transactions,
       coalesce(vl.void_line_amount, 0)              AS void_line_amount,
       coalesce(vl.void_ring_lines, 0)               AS void_ring_lines,
       coalesce(vt.voids, 0)                         AS voids,
       coalesce(vt.void_amount, 0)                   AS void_amount,
       coalesce(vl.void_lines, 0) + coalesce(vt.voids, 0)             AS total_voids,
       coalesce(vl.void_line_amount, 0) + coalesce(vt.void_amount, 0) AS total_void_amount,
       coalesce(vl.deposit_void_lines, 0)            AS deposit_void_lines,
       coalesce(vl.deposit_void_line_amount, 0)      AS deposit_void_line_amount,
       coalesce(vl.voided_txn_void_lines, 0)         AS voided_txn_void_lines,
       coalesce(vl.voided_txn_void_line_amount, 0)   AS voided_txn_void_line_amount
FROM d
LEFT JOIN vl USING (store_id, store_code, business_date)
LEFT JOIN vt USING (store_id, store_code, business_date);

-- Per counted transaction: payments vs lines + tax. Equal on all 4,910 transactions of 22-30 Sep 2026;
-- a row here means a transaction the rules do not explain.
CREATE VIEW report.rpt_payment_gap AS
SELECT e.store_id, e.store_code, e.business_date, e.file_id, e.transaction_id AS ticket_number,
       e.register_id AS register, e.started_at, e.ended_at, e.event_type, e.total_grand,
       coalesce(t.paid, 0)                           AS paid,
       coalesce(l.lines, 0) + coalesce(x.tax, 0)     AS lines_plus_tax,
       coalesce(t.paid, 0) - coalesce(l.lines, 0) - coalesce(x.tax, 0) AS gap
FROM pjr.event e
LEFT JOIN LATERAL (SELECT sum(amount) AS paid FROM pjr.tender_line WHERE file_id = e.file_id AND status = 'normal') t ON true
LEFT JOIN LATERAL (SELECT sum(sales_amount) AS lines FROM pjr.event_line WHERE file_id = e.file_id AND status = 'normal'
                     AND line_type IN ('ItemLine', 'MerchandiseCodeLine', 'FuelLine')) l ON true
LEFT JOIN LATERAL (SELECT sum(tax_collected) AS tax FROM pjr.event_tax WHERE file_id = e.file_id AND status = 'normal') x ON true
WHERE e.counts_as_sale
  AND coalesce(t.paid, 0) <> coalesce(l.lines, 0) + coalesce(x.tax, 0);

-- One row per store per business day (the StoreClose summary).
CREATE VIEW report.rpt_day_summary AS
WITH f AS (
    SELECT store_id, store_code, business_date, count(*) AS files, min(shift_begin) AS period_opened,
           count(DISTINCT shift_begin) AS shifts
    FROM pjr.pjr_file GROUP BY store_id, store_code, business_date
), t AS (
    SELECT store_id, store_code, business_date,
           count(*) FILTER (WHERE counts_as_sale)                              AS customers,
           count(*) FILTER (WHERE counts_as_sale AND is_outside)               AS pump_sales,
           count(*) FILTER (WHERE is_suspended)                                AS prepay_deposits,
           min(started_at) AS first_event, max(ended_at) AS last_event
    FROM pjr.event GROUP BY store_id, store_code, business_date
), tx AS (
    SELECT e.store_id, e.store_code, e.business_date, sum(x.tax_collected) AS tax
    FROM pjr.event e JOIN pjr.event_tax x USING (file_id)
    WHERE e.counts_as_sale AND x.status = 'normal'
    GROUP BY e.store_id, e.store_code, e.business_date
), s AS (
    SELECT store_id, store_code, business_date,
           sum(net_amount)   FILTER (WHERE sales_class = 'merchandise')    AS merchandise_net,
           sum(net_amount)   FILTER (WHERE sales_class = 'lottery_sale')   AS lottery_sales,
           sum(net_amount)   FILTER (WHERE sales_class = 'lottery_payout') AS lottery_payouts,
           sum(gross_amount) FILTER (WHERE sales_class = 'fuel')           AS fuel_sales,
           sum(net_amount)   FILTER (WHERE sales_class = 'fuel')           AS fuel_net,
           sum(fuel_volume)                                                AS fuel_gallons,
           sum(promo_amount)                                               AS promotions,
           sum(net_amount)                                                 AS lines_net
    FROM report.v_sold_line GROUP BY store_id, store_code, business_date
), p AS (
    SELECT e.store_id, e.store_code, e.business_date, sum(t.amount) AS payments_net
    FROM pjr.event e JOIN pjr.tender_line t USING (file_id)
    WHERE e.counts_as_sale AND t.status = 'normal'
    GROUP BY e.store_id, e.store_code, e.business_date
), g AS (
    SELECT store_id, store_code, business_date, count(*) AS payment_gaps
    FROM report.rpt_payment_gap GROUP BY store_id, store_code, business_date
), c AS (
    SELECT store_id, store_code, business_date,
           count(*) FILTER (WHERE kind = 'safe_drop')                 AS safe_drops,
           coalesce(sum(amount) FILTER (WHERE kind = 'safe_drop'), 0) AS safe_drop_amount,
           count(*) FILTER (WHERE kind = 'payout')                    AS payouts,
           coalesce(sum(amount) FILTER (WHERE kind = 'payout'), 0)    AS payout_amount,
           count(*) FILTER (WHERE kind = 'safe_loan')                 AS safe_loans,
           coalesce(sum(amount) FILTER (WHERE kind = 'safe_loan'), 0) AS safe_loan_amount
    FROM report.rpt_cash_movement GROUP BY store_id, store_code, business_date
), x AS (
    SELECT store_id, store_code, business_date,
           count(*) FILTER (WHERE kind = 'no_sale')                   AS no_sales,
           count(*) FILTER (WHERE kind = 'void_line')                 AS void_lines,
           count(*) FILTER (WHERE kind = 'void')                      AS voids,
           count(*) FILTER (WHERE kind = 'refund')                    AS refunds
    FROM report.rpt_suspicious_event GROUP BY store_id, store_code, business_date
), lp AS (
    SELECT store_id, store_code, business_date, payouts AS lottery_payout_count FROM report.rpt_lottery_payout_daily
)
SELECT f.store_id, f.store_code, f.business_date, f.files, f.period_opened, t.first_event, t.last_event,
       t.customers, t.pump_sales, t.prepay_deposits,
       s.merchandise_net, s.lottery_sales, s.lottery_payouts, s.fuel_sales, s.fuel_net, s.fuel_gallons,
       s.promotions, tx.tax,
       p.payments_net,
       coalesce(g.payment_gaps, 0)                              AS payment_gaps,
       coalesce(g.payment_gaps, 0) = 0                          AS payments_tie,
       coalesce(c.safe_drops, 0) AS safe_drops, coalesce(c.safe_drop_amount, 0) AS safe_drop_amount,
       coalesce(c.payouts, 0) AS payouts, coalesce(c.payout_amount, 0) AS payout_amount,
       coalesce(c.safe_loans, 0) AS safe_loans, coalesce(c.safe_loan_amount, 0) AS safe_loan_amount,
       coalesce(x.no_sales, 0) AS no_sales, coalesce(x.void_lines, 0) AS void_lines, coalesce(x.voids, 0) AS voids,
       coalesce(x.refunds, 0) AS refunds,
       -- lottery payouts are NOT subtracted from total sales
       coalesce(s.lines_net, 0) - coalesce(s.lottery_payouts, 0)                         AS total_sales_before_tax,
       coalesce(s.lines_net, 0) - coalesce(s.lottery_payouts, 0) + coalesce(tx.tax, 0)   AS total_sales_with_tax,
       coalesce(lp.lottery_payout_count, 0)                     AS lottery_payout_count,
       -- item sales = scanned items + department rings (lottery and fuel are separate)
       coalesce(s.merchandise_net, 0)                           AS item_sales,
       f.shifts
FROM f
LEFT JOIN t  USING (store_id, store_code, business_date)
LEFT JOIN tx USING (store_id, store_code, business_date)
LEFT JOIN s  USING (store_id, store_code, business_date)
LEFT JOIN p  USING (store_id, store_code, business_date)
LEFT JOIN g  USING (store_id, store_code, business_date)
LEFT JOIN c  USING (store_id, store_code, business_date)
LEFT JOIN x  USING (store_id, store_code, business_date)
LEFT JOIN lp USING (store_id, store_code, business_date);
