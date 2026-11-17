-- =====================================================================================
-- V7: fields seen first on store 1409_UT-84790 (RCM). verifone_db only.
--
-- 1. cashier/@empNum can be the cashier's login name ("satnnam"), not only a number:
--    master.cashier_versions.employee_number integer -> text, kept as written. Existing numbers keep
--    their digits. master.cashier_name_history reads the column, so it is dropped and recreated as in V1.
-- 2. trPayline/trpHouseAcct (text = account number, @name) on an IN-HOUSE tender: the payment's house
--    account, in the same master.house_accounts as trHeader/trHouseAcct.
-- =====================================================================================

DROP VIEW master.cashier_name_history;

ALTER TABLE master.cashier_versions
    ALTER COLUMN employee_number TYPE text USING employee_number::text,
    ADD CONSTRAINT ck_cashier_versions_employee_number CHECK (employee_number IS NULL OR btrim(employee_number) <> '');

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

ALTER TABLE cpj.payment_lines
    ADD COLUMN house_account_version_id bigint
        REFERENCES master.house_account_versions (house_account_version_id) ON DELETE RESTRICT;  -- trpHouseAcct
CREATE INDEX ix_payment_lines_house_account ON cpj.payment_lines (house_account_version_id)
    WHERE house_account_version_id IS NOT NULL;
