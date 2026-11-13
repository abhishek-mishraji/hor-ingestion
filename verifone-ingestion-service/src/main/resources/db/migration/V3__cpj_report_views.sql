-- =====================================================================================
-- V3: report.rpt_* views, the contract reporting-service reads for Verifone stores.
--
-- Same view names and columns as the CPJ prototype (HOR-XML/cpj-platform V3, V5, V6) and as
-- gilbarco_db report.rpt_*, so reporting-service and the client screens read both POS databases
-- the same way. Built only from what the CPJ files hold (report.sale_lines, cpj.*, master.*).
-- Rules are the V1 ones (R1-R4); nothing here is entered by hand.
--
-- Replaces the report.rpt_* / public.rpt_* views that were created by hand during development.
-- Safe to run again: every view is dropped first.
-- =====================================================================================

DROP VIEW IF EXISTS
    public.rpt_day_summary, public.rpt_items_sold_daily, public.rpt_department_daily,
    public.rpt_fuel_daily, public.rpt_payment_daily, public.rpt_cash_movement,
    public.rpt_suspicious_event, public.rpt_suspicious_daily, public.rpt_lottery_daily,
    public.rpt_lottery_payout_daily, public.rpt_void_daily
    CASCADE;

DROP VIEW IF EXISTS
    report.rpt_day_summary, report.rpt_items_sold_daily, report.rpt_department_daily,
    report.rpt_fuel_daily, report.rpt_payment_daily, report.rpt_cash_movement,
    report.rpt_suspicious_event, report.rpt_suspicious_daily, report.rpt_lottery_daily,
    report.rpt_lottery_payout_daily, report.rpt_void_daily,
    report.rpt_ticket_tax, report.rpt_ticket_line, report.rpt_payment_line,
    report.rpt_sold_line, report.rpt_ticket
    CASCADE;

-- Every ticket of every file (counted or not), with store code, cashier and start time.
-- file_id + ticket_seq address a ticket in the API (ticket drill-down).
-- start = end - duration: the POS writes the end time and the seconds the ticket was open.
-- Times are the store's wall clock (timestamp without time zone, in master.stores.timezone), like the
-- PJR times in gilbarco_db, so they read the same whatever the time zone of the reader.
CREATE VIEW report.rpt_ticket AS
SELECT f.store_id,
       s.store_code,
       f.business_date,
       t.cpj_file_id                                                   AS file_id,
       t.seq_in_file                                                   AS ticket_seq,
       t.ticket_id,
       t.trans_type,
       t.trans_type IN ('sale', 'network sale', 'refund sale')
           AND NOT t.is_suspended AND NOT t.is_fuel_prepay AND NOT t.is_rollback AS counts_as_sale,   -- R1
       t.is_suspended, t.is_fuel_prepay, t.is_rollback, t.is_prepay_completion, t.is_recalled,
       r.pos_num,
       t.tr_seq,
       cv.name                                                         AS cashier_name,
       t.ticket_at AT TIME ZONE s.timezone                             AS ticket_local,
       (t.ticket_at - coalesce(t.duration_sec, 0) * interval '1 second') AT TIME ZONE s.timezone AS started_at,
       t.duration_sec,
       t.total, t.total_no_tax, t.total_tax,
       t.misc_ref,
       ro.pos_num AS orig_pos_num,   t.orig_tr_seq,
       rr.pos_num AS recall_pos_num, t.recall_tr_seq,
       EXISTS (SELECT 1 FROM cpj.ticket_age_checks a WHERE a.ticket_id = t.ticket_id) AS has_age_check,
       f.file_path                                                     AS file_name
FROM cpj.tickets t
JOIN cpj.cpj_files f                 ON f.cpj_file_id = t.cpj_file_id
JOIN master.stores s                 ON s.store_id = f.store_id
JOIN master.registers r              ON r.register_id = t.register_id
LEFT JOIN master.registers ro        ON ro.register_id = t.orig_register_id
LEFT JOIN master.registers rr        ON rr.register_id = t.recall_register_id
LEFT JOIN cpj.ticket_cashiers c      ON c.ticket_id = t.ticket_id AND c.cashier_role = 'CASHIER'
LEFT JOIN master.cashier_versions cv ON cv.cashier_version_id = c.cashier_version_id;

-- Every sold line of a counted ticket (report.sale_lines) with its ticket's store code, cashier and times
CREATE VIEW report.rpt_sold_line AS
SELECT t.store_id, t.store_code, t.business_date, t.file_id, t.ticket_seq, t.ticket_id, sl.line_id,
       t.tr_seq            AS ticket_number,
       t.trans_type,
       t.pos_num           AS register,
       t.cashier_name      AS cashier,
       t.ticket_local      AS sold_at,
       t.started_at,
       t.duration_sec,
       t.total             AS ticket_total,
       sl.line_no, sl.line_type, sl.line_kind, sl.is_void_line,
       sl.dept_number, sl.dept_name, sl.dept_type, sl.sales_class,
       sl.upc, sl.description, sl.quantity, sl.unit_price,
       sl.line_total       AS gross_amount,      -- before mix-and-match (R3)
       sl.promo_amount, sl.net_amount, sl.discount_amount,
       sl.fuel_grade, sl.fuel_price_level, sl.fuel_volume, sl.fuel_position,
       l.voided_line_no    AS void_line_ix
FROM report.sale_lines sl
JOIN report.rpt_ticket t  ON t.ticket_id = sl.ticket_id
JOIN cpj.ticket_lines l   ON l.line_id = sl.line_id;

-- Every payment line of every ticket; signed_amount is negative for a cancelled payment
CREATE VIEW report.rpt_payment_line AS
SELECT t.store_id, t.store_code, t.business_date, t.file_id, t.ticket_seq, t.ticket_id,
       pl.line_no,
       pt.paycode,
       pl.payline_type,
       pl.amount,
       CASE WHEN pl.payline_type = 'cancel' THEN -pl.amount ELSE pl.amount END AS signed_amount,
       ct.card_name,
       cd.entry_method
FROM cpj.payment_lines pl
JOIN report.rpt_ticket t              ON t.ticket_id = pl.ticket_id
JOIN master.payment_type_versions pv  ON pv.payment_type_version_id = pl.payment_type_version_id
JOIN master.payment_types pt          ON pt.payment_type_id = pv.payment_type_id
LEFT JOIN cpj.payment_card_details cd ON cd.payment_line_id = pl.payment_line_id
LEFT JOIN master.card_types ct        ON ct.card_type_id = cd.card_type_id;

-- Every line of a ticket as written (ticket drill-down)
CREATE VIEW report.rpt_ticket_line AS
SELECT t.store_id, t.store_code, t.file_id, t.ticket_seq, t.ticket_id,
       l.line_no,
       l.line_type,
       coalesce(iv.description, l.pos_description, dv.name)            AS description,
       d.dept_number,
       dv.name                                                         AS dept_name,
       i.upc,
       l.quantity * l.sign                                             AS signed_quantity,
       l.unit_price,
       l.line_total,
       coalesce(p.promo_amount, 0) * l.sign                            AS promo_amount,
       l.line_total - coalesce(p.promo_amount, 0) * l.sign             AS net_amount,
       lf.volume                                                       AS fuel_volume,
       l.voided_line_no                                                AS void_line_ix
FROM cpj.ticket_lines l
JOIN report.rpt_ticket t                ON t.ticket_id = l.ticket_id
LEFT JOIN master.department_versions dv ON dv.department_version_id = l.department_version_id
LEFT JOIN master.departments d          ON d.department_id = dv.department_id
LEFT JOIN master.item_versions iv       ON iv.item_version_id = l.item_version_id
LEFT JOIN master.items i                ON i.item_id = iv.item_id
LEFT JOIN (SELECT line_id, sum(promo_amount) AS promo_amount FROM cpj.line_promotions GROUP BY line_id) p
                                        ON p.line_id = l.line_id
LEFT JOIN cpj.line_fuel lf              ON lf.line_id = l.line_id;

-- Taxes of a ticket (ticket drill-down)
CREATE VIEW report.rpt_ticket_tax AS
SELECT t.store_id, t.store_code, t.file_id, t.ticket_seq, t.ticket_id,
       x.tax_sysid,
       tv.name AS tax_name,
       tx.is_refund_tax,
       tx.tax_rate,
       tx.taxable_amount,
       tx.tax_amount
FROM cpj.ticket_taxes tx
JOIN report.rpt_ticket t        ON t.ticket_id = tx.ticket_id
JOIN master.tax_versions tv     ON tv.tax_version_id = tx.tax_version_id
JOIN master.taxes x             ON x.tax_id = tv.tax_id;

-- Items sold per day: scanned items + department rings, merchandise only (lottery and fuel are separate)
CREATE VIEW report.rpt_items_sold_daily AS
SELECT store_id, store_code, business_date,
       CASE WHEN line_kind = 'item' THEN 'scanned' ELSE 'department ring' END AS item_type,
       upc,
       max(description)                     AS description,
       dept_number,
       max(dept_name)                       AS dept_name,
       sales_class,
       sum(quantity)                        AS units_sold,
       sum(gross_amount)                    AS gross_sales,
       sum(promo_amount)                    AS promotions,
       sum(net_amount)                      AS net_sales,
       count(DISTINCT ticket_id)            AS tickets
FROM report.rpt_sold_line
WHERE line_kind IN ('item', 'department')
  AND sales_class = 'merchandise'
GROUP BY store_id, store_code, business_date, 4, upc, dept_number, sales_class;

-- Department sales per day (items, department rings, lottery and fuel; sales_class tells them apart).
-- refund and void_amount are positive; discount is informational (already inside the line totals).
CREATE VIEW report.rpt_department_daily AS
SELECT store_id, store_code, business_date, dept_number,
       max(dept_name)                       AS dept_name,
       sales_class,
       sum(quantity)                        AS units_sold,
       sum(gross_amount)                    AS gross_sales,
       sum(promo_amount)                    AS promotions,
       coalesce(sum(discount_amount), 0)    AS discount,
       coalesce(-sum(gross_amount) FILTER (WHERE NOT is_void_line AND gross_amount < 0
                                             AND sales_class <> 'lottery_payout'), 0) AS refund,
       coalesce(-sum(gross_amount) FILTER (WHERE is_void_line), 0)                    AS void_amount,
       sum(net_amount)                      AS net_sales,
       sum(fuel_volume)                     AS fuel_gallons,
       count(*)                             AS lines
FROM report.rpt_sold_line
GROUP BY store_id, store_code, business_date, dept_number, sales_class;

-- Lottery per day: sales and payouts side by side, both positive amounts, nothing netted (R4)
CREATE VIEW report.rpt_lottery_daily AS
SELECT store_id, store_code, business_date,
       count(*)      FILTER (WHERE sales_class = 'lottery_sale')                   AS sales_lines,
       sum(quantity) FILTER (WHERE sales_class = 'lottery_sale')                   AS sales_units,
       coalesce(sum(net_amount)  FILTER (WHERE sales_class = 'lottery_sale'), 0)   AS sales_amount,
       count(*)      FILTER (WHERE sales_class = 'lottery_payout')                 AS payouts,
       coalesce(-sum(net_amount) FILTER (WHERE sales_class = 'lottery_payout'), 0) AS payout_amount
FROM report.rpt_sold_line
WHERE sales_class IN ('lottery_sale', 'lottery_payout')
GROUP BY store_id, store_code, business_date;

-- Lottery payouts per day (shown on their own, never subtracted)
CREATE VIEW report.rpt_lottery_payout_daily AS
SELECT store_id, store_code, business_date,
       count(*)              AS payouts,
       -sum(gross_amount)    AS payout_amount      -- positive: money paid to winners
FROM report.rpt_sold_line
WHERE sales_class = 'lottery_payout'
GROUP BY store_id, store_code, business_date;

-- Fuel per day by grade and price level; ppg_discount = loyalty cents-per-gallon discount total
CREATE VIEW report.rpt_fuel_daily AS
SELECT store_id, store_code, business_date, fuel_grade, fuel_price_level,
       sum(fuel_volume)                         AS gallons,
       sum(gross_amount)                        AS amount,
       count(*) FILTER (WHERE fuel_volume > 0)  AS fuel_sales,
       sum(discount_amount)                     AS ppg_discount
FROM report.rpt_sold_line
WHERE line_type = 'postFuel'
GROUP BY store_id, store_code, business_date, fuel_grade, fuel_price_level;

-- Payments per day per payment type, as recorded. Change is merged into CASH.
CREATE VIEW report.rpt_payment_daily AS
SELECT p.store_id, p.store_code, p.business_date,
       CASE WHEN p.paycode = 'Change' THEN 'CASH' ELSE p.paycode END                            AS payment_type,
       count(*) FILTER (WHERE p.paycode <> 'Change' AND t.trans_type <> 'refund sale')          AS payments,
       coalesce(sum(p.signed_amount) FILTER (WHERE t.trans_type <> 'refund sale'), 0)           AS sales_amount,
       count(*) FILTER (WHERE t.trans_type = 'refund sale')                                     AS refunds,
       coalesce(sum(p.amount) FILTER (WHERE t.trans_type = 'refund sale'), 0)                   AS refunded_amount,
       sum(p.signed_amount)                                                                     AS net_amount
FROM report.rpt_payment_line p
JOIN report.rpt_ticket t ON t.ticket_id = p.ticket_id
WHERE t.counts_as_sale
GROUP BY p.store_id, p.store_code, p.business_date, 4;

-- Cash movements: safe drops, payouts, safe loans (one row each); reference = payout reason or drop number
CREATE VIEW report.rpt_cash_movement AS
SELECT t.store_id, t.store_code, t.business_date,
       CASE t.trans_type WHEN 'safedrop' THEN 'safe_drop' WHEN 'payout' THEN 'payout' ELSE 'safe_loan' END AS kind,
       t.ticket_local      AS occurred_at,
       t.pos_num           AS register,
       t.cashier_name      AS cashier,
       t.tr_seq            AS ticket_number,
       t.file_id,
       t.ticket_seq,
       t.total             AS amount,
       t.misc_ref          AS reference,
       (SELECT string_agg(DISTINCT p.paycode, ', ') FROM report.rpt_payment_line p
         WHERE p.ticket_id = t.ticket_id) AS paid_with
FROM report.rpt_ticket t
WHERE t.trans_type IN ('safedrop', 'payout', 'safeloan');

-- Suspicious activity, every occurrence: no_sale, void_line, void, refund, refund_void.
-- started_at / ended_at = when the ticket was started and finished.
CREATE VIEW report.rpt_suspicious_event AS
SELECT t.store_id, t.store_code, t.business_date, t.ticket_local AS occurred_at, 'no_sale'::text AS kind,
       t.pos_num AS register, t.cashier_name AS cashier, t.tr_seq AS ticket_number, t.file_id, t.ticket_seq,
       NULL::numeric AS amount, NULL::text AS item, NULL::numeric AS quantity, t.duration_sec,
       'drawer opened without a sale'::text AS detail,
       t.started_at, t.ticket_local AS ended_at
FROM report.rpt_ticket t
WHERE t.trans_type = 'nosale'
UNION ALL
SELECT s.store_id, s.store_code, s.business_date, s.sold_at, 'void_line',
       s.register, s.cashier, s.ticket_number, s.file_id, s.ticket_seq,
       s.gross_amount, s.description, s.quantity, s.duration_sec,
       'cancels line ' || coalesce(s.void_line_ix::text, '?') || '; ticket then paid ' || coalesce(s.ticket_total::text, '?'),
       s.started_at, s.sold_at
FROM report.rpt_sold_line s
WHERE s.is_void_line
UNION ALL
SELECT t.store_id, t.store_code, t.business_date, t.ticket_local, 'void',
       t.pos_num, t.cashier_name, t.tr_seq, t.file_id, t.ticket_seq,
       t.total_no_tax, i.items, NULL, t.duration_sec,
       'whole ticket cancelled; total with tax ' || coalesce(t.total::text, '0'),
       t.started_at, t.ticket_local
FROM report.rpt_ticket t
LEFT JOIN LATERAL (SELECT string_agg(l.description, ', ' ORDER BY l.line_no) AS items FROM report.rpt_ticket_line l
                   WHERE l.ticket_id = t.ticket_id AND l.line_type NOT LIKE 'void %') i ON true
WHERE t.trans_type = 'void'
UNION ALL
SELECT s.store_id, s.store_code, s.business_date, s.sold_at, 'refund',
       s.register, s.cashier, s.ticket_number, s.file_id, s.ticket_seq,
       s.gross_amount, s.description, s.quantity, s.duration_sec,
       coalesce('money back: ' || m.back, 'no money handed back on this ticket'),
       s.started_at, s.sold_at
FROM report.rpt_sold_line s
LEFT JOIN LATERAL (SELECT string_agg(p.paycode || ' ' || p.amount, ', ') AS back FROM report.rpt_payment_line p
                   WHERE p.ticket_id = s.ticket_id AND p.payline_type = 'cancel') m ON true
WHERE s.gross_amount < 0 AND NOT s.is_void_line AND s.sales_class <> 'lottery_payout'
UNION ALL
SELECT t.store_id, t.store_code, t.business_date, t.ticket_local, 'refund_void',
       t.pos_num, t.cashier_name, t.tr_seq, t.file_id, t.ticket_seq,
       t.total, i.items, NULL, t.duration_sec,
       'refund started and cancelled; no money handed back',
       t.started_at, t.ticket_local
FROM report.rpt_ticket t
LEFT JOIN LATERAL (SELECT string_agg(l.description, ', ' ORDER BY l.line_no) AS items FROM report.rpt_ticket_line l
                   WHERE l.ticket_id = t.ticket_id AND l.line_type NOT LIKE 'void %') i ON true
WHERE t.trans_type = 'refund void';

-- Suspicious activity: daily totals per kind
CREATE VIEW report.rpt_suspicious_daily AS
SELECT store_id, store_code, business_date, kind, count(*) AS occurrences, sum(amount) AS amount
FROM report.rpt_suspicious_event
GROUP BY store_id, store_code, business_date, kind;

-- Voids per day, every way of counting them: void lines, voided units, transactions with a void line,
-- whole voids, and void lines + whole voids together. Amounts are positive.
CREATE VIEW report.rpt_void_daily AS
WITH d AS (
    SELECT DISTINCT store_id, store_code, business_date FROM report.rpt_ticket
), vl AS (
    SELECT store_id, business_date,
           count(*)                     AS void_lines,
           -sum(quantity)               AS voided_units,
           count(DISTINCT ticket_id)    AS void_line_transactions,
           -sum(gross_amount)           AS void_line_amount
    FROM report.rpt_sold_line WHERE is_void_line
    GROUP BY store_id, business_date
), vt AS (
    SELECT store_id, business_date,
           count(*)                         AS voids,
           coalesce(sum(total_no_tax), 0)   AS void_amount          -- whole tickets, before tax
    FROM report.rpt_ticket WHERE trans_type = 'void'
    GROUP BY store_id, business_date
)
SELECT d.store_id, d.store_code, d.business_date,
       coalesce(vl.void_lines, 0)                    AS void_lines,
       coalesce(vl.voided_units, 0)                  AS voided_units,
       coalesce(vl.void_line_transactions, 0)        AS void_line_transactions,
       coalesce(vl.void_line_amount, 0)              AS void_line_amount,
       coalesce(vt.voids, 0)                         AS voids,
       coalesce(vt.void_amount, 0)                   AS void_amount,
       coalesce(vl.void_lines, 0) + coalesce(vt.voids, 0)             AS total_voids,
       coalesce(vl.void_line_amount, 0) + coalesce(vt.void_amount, 0) AS total_void_amount
FROM d
LEFT JOIN vl USING (store_id, business_date)
LEFT JOIN vt USING (store_id, business_date);

-- One row per store per business day.
-- sales_counter = change of the POS overall-sales counter over the day's files (START -> END);
-- counter_ties = it equals the counted tickets. Lottery payouts are NOT subtracted from total sales.
CREATE VIEW report.rpt_day_summary AS
WITH t AS (
    SELECT store_id, business_date,
           count(*)          FILTER (WHERE counts_as_sale) AS customers,
           sum(total)        FILTER (WHERE counts_as_sale) AS ticket_totals,
           sum(total_no_tax) FILTER (WHERE counts_as_sale) AS ticket_totals_no_tax,
           sum(total_tax)    FILTER (WHERE counts_as_sale) AS tax
    FROM report.rpt_ticket GROUP BY store_id, business_date
), s AS (
    SELECT store_id, business_date,
           sum(net_amount)   FILTER (WHERE sales_class = 'merchandise')    AS merchandise_net,
           sum(net_amount)   FILTER (WHERE sales_class = 'lottery_sale')   AS lottery_sales,
           sum(net_amount)   FILTER (WHERE sales_class = 'lottery_payout') AS lottery_payouts,
           sum(gross_amount) FILTER (WHERE sales_class = 'fuel')           AS fuel_sales,
           sum(fuel_volume)  FILTER (WHERE line_type = 'postFuel')         AS fuel_gallons,
           sum(promo_amount)                                               AS promotions
    FROM report.rpt_sold_line GROUP BY store_id, business_date
), p AS (
    SELECT p.store_id, p.business_date,
           sum(p.signed_amount)                                                AS payments_net,
           coalesce(sum(p.amount) FILTER (WHERE p.payline_type = 'cancel'), 0) AS refunded_money
    FROM report.rpt_payment_line p
    JOIN report.rpt_ticket t ON t.ticket_id = p.ticket_id
    WHERE t.counts_as_sale GROUP BY p.store_id, p.business_date
), c AS (
    SELECT store_id, business_date,
           count(*) FILTER (WHERE kind = 'safe_drop')                 AS safe_drops,
           coalesce(sum(amount) FILTER (WHERE kind = 'safe_drop'), 0) AS safe_drop_amount,
           count(*) FILTER (WHERE kind = 'payout')                    AS payouts,
           coalesce(sum(amount) FILTER (WHERE kind = 'payout'), 0)    AS payout_amount,
           count(*) FILTER (WHERE kind = 'safe_loan')                 AS safe_loans,
           coalesce(sum(amount) FILTER (WHERE kind = 'safe_loan'), 0) AS safe_loan_amount
    FROM report.rpt_cash_movement GROUP BY store_id, business_date
), x AS (
    SELECT store_id, business_date,
           count(*) FILTER (WHERE kind = 'no_sale')     AS no_sales,
           count(*) FILTER (WHERE kind = 'void_line')   AS void_lines,
           count(*) FILTER (WHERE kind = 'void')        AS voids,
           count(*) FILTER (WHERE kind = 'refund')      AS refunds,
           count(*) FILTER (WHERE kind = 'refund_void') AS refund_voids
    FROM report.rpt_suspicious_event GROUP BY store_id, business_date
), lp AS (
    SELECT store_id, business_date, payouts AS lottery_payout_count FROM report.rpt_lottery_payout_daily
)
SELECT d.store_id, st.store_code, d.business_date, d.files,
       d.period_opened AT TIME ZONE st.timezone AS period_opened,      -- store wall clock, as rpt_ticket
       d.period_closed AT TIME ZONE st.timezone AS period_closed,
       d.sales_counter, t.ticket_totals, (d.sales_counter = t.ticket_totals) AS counter_ties,
       t.customers, s.merchandise_net, s.lottery_sales, s.lottery_payouts, s.fuel_sales, s.fuel_gallons,
       s.promotions, t.tax, p.payments_net, p.refunded_money,
       coalesce(c.safe_drops, 0) AS safe_drops, coalesce(c.safe_drop_amount, 0) AS safe_drop_amount,
       coalesce(c.payouts, 0) AS payouts, coalesce(c.payout_amount, 0) AS payout_amount,
       coalesce(c.safe_loans, 0) AS safe_loans, coalesce(c.safe_loan_amount, 0) AS safe_loan_amount,
       coalesce(x.no_sales, 0) AS no_sales, coalesce(x.void_lines, 0) AS void_lines, coalesce(x.voids, 0) AS voids,
       coalesce(x.refunds, 0) AS refunds, coalesce(x.refund_voids, 0) AS refund_voids,
       t.ticket_totals_no_tax - coalesce(s.lottery_payouts, 0)  AS total_sales_before_tax,
       t.ticket_totals - coalesce(s.lottery_payouts, 0)         AS total_sales_with_tax,
       coalesce(lp.lottery_payout_count, 0)                     AS lottery_payout_count,
       coalesce(s.merchandise_net, 0)                           AS item_sales,     -- scanned items + department rings
       d.cash_deposit, d.check_deposit                                             -- safe drops paid in cash / check
FROM report.daily_summary d
LEFT JOIN t  USING (store_id, business_date)
LEFT JOIN s  USING (store_id, business_date)
LEFT JOIN p  USING (store_id, business_date)
LEFT JOIN c  USING (store_id, business_date)
LEFT JOIN x  USING (store_id, business_date)
LEFT JOIN lp USING (store_id, business_date)
JOIN master.stores st ON st.store_id = d.store_id;

-- reporting_svc reads these views (SELECT only); skipped where the role is absent, as in V1
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'reporting_svc') THEN
        GRANT SELECT ON ALL TABLES IN SCHEMA report TO reporting_svc;
    END IF;
END $$;
