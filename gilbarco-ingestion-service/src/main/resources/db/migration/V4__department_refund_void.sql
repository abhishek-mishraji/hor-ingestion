-- =====================================================================================
-- V4: department discount, refund and void amounts, from the PJR lines (for the monthly department
-- report). The existing columns of report.rpt_department_daily are unchanged; three are added at the end.
--   discount    = line DiscountAmount (PJR writes it negative; shown positive). Already inside
--                 promotions (= gross - net), so promotion without discounts = promotions - discount.
--   refund      = refunded lines (negative gross, lottery payouts excluded), shown positive.
--   void_amount = voided lines on counted transactions (v_void_line 'sale', regular price x quantity).
--                 Voided lines are never in gross or net; NULL for a department ring with no price.
-- =====================================================================================
CREATE OR REPLACE VIEW report.rpt_department_daily AS
WITH s AS (
    SELECT s.store_id,
           s.store_code,
           s.business_date,
           s.dept_number,
           max(s.dept_name)                         AS dept_name,
           s.sales_class,
           sum(s.quantity)                          AS units_sold,
           sum(s.gross_amount)                      AS gross_sales,
           sum(s.promo_amount)                      AS promotions,
           sum(s.net_amount)                        AS net_sales,
           sum(s.fuel_volume)                       AS fuel_gallons,
           count(*)                                 AS lines,
           coalesce(-sum(l.discount_amount), 0)     AS discount,
           coalesce(-sum(s.gross_amount) FILTER (WHERE s.gross_amount < 0 AND s.sales_class <> 'lottery_payout'), 0) AS refund
    FROM report.v_sold_line s
    JOIN pjr.event_line l ON l.file_id = s.file_id AND l.line_no = s.line_no
    GROUP BY s.store_id, s.store_code, s.business_date, s.dept_number, s.sales_class
), v AS (
    SELECT v.store_id,
           v.store_code,
           v.business_date,
           v.dept_number,
           max(CASE WHEN v.line_type = 'FuelLine' THEN 'Fuel' ELSE v.dept_name END) AS dept_name,
           CASE WHEN v.line_type = 'FuelLine' THEN 'fuel' ELSE coalesce(d.sales_class, 'merchandise') END AS sales_class,
           coalesce(sum(v.void_value), 0)           AS void_amount
    FROM report.v_void_line v
    LEFT JOIN master.departments d ON d.store_id = v.store_id AND d.dept_number = v.dept_number
    WHERE v.where_found = 'sale'
    GROUP BY v.store_id, v.store_code, v.business_date, v.dept_number, 6
)
SELECT coalesce(s.store_id, v.store_id)             AS store_id,
       coalesce(s.store_code, v.store_code)         AS store_code,
       coalesce(s.business_date, v.business_date)   AS business_date,
       coalesce(s.dept_number, v.dept_number)       AS dept_number,
       coalesce(s.dept_name, v.dept_name)           AS dept_name,
       coalesce(s.sales_class, v.sales_class)       AS sales_class,
       coalesce(s.units_sold, 0)                    AS units_sold,
       coalesce(s.gross_sales, 0)                   AS gross_sales,
       coalesce(s.promotions, 0)                    AS promotions,
       coalesce(s.net_sales, 0)                     AS net_sales,
       s.fuel_gallons,
       coalesce(s.lines, 0)                         AS lines,
       coalesce(s.discount, 0)                      AS discount,
       coalesce(s.refund, 0)                        AS refund,
       coalesce(v.void_amount, 0)                   AS void_amount
FROM s
FULL JOIN v ON v.store_id = s.store_id AND v.business_date = s.business_date
           AND v.dept_number IS NOT DISTINCT FROM s.dept_number AND v.sales_class = s.sales_class;
