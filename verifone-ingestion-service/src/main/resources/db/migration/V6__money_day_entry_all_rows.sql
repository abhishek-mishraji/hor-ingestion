-- =====================================================================================
-- V6: every Money Out row of the Daily report can be entered by a platform admin or HOR staff member.
-- Same migration in both vendor databases (verifone_db V6, gilbarco_db V9): keep them identical.
--
-- Adds to report.money_day_entry (V5 / V8) one nullable column per row. NULL = "use the POS figure"
-- (or 0 where the POS has none), a number = the entered figure. Existing entries keep their values.
--   Credit Card 2         generic_card_1, generic_card_2                   (not in the POS: 0)
--   Bank Deposits         ebt                                              (POS: EBT payments)
--   Cash Purchases        cash_purchases (POS paid-outs), cash_expenses, general_account_paid,
--                         other_income (POS pay-ins), cust_account_sales (POS house charges)
--   Other Money Received  cust_account_payments, general_account_received (not in the POS: 0)
--   ATM Reading           atm_trans (count), atm_dispensed, atm_fee        (not in the POS: 0; display only)
-- =====================================================================================
ALTER TABLE report.money_day_entry
    ADD COLUMN generic_card_1            numeric(12,2),
    ADD COLUMN generic_card_2            numeric(12,2),
    ADD COLUMN ebt                       numeric(12,2),
    ADD COLUMN cash_purchases            numeric(12,2),
    ADD COLUMN cash_expenses             numeric(12,2),
    ADD COLUMN general_account_paid      numeric(12,2),
    ADD COLUMN other_income              numeric(12,2),
    ADD COLUMN cust_account_sales        numeric(12,2),
    ADD COLUMN cust_account_payments     numeric(12,2),
    ADD COLUMN general_account_received  numeric(12,2),
    ADD COLUMN atm_trans                 integer,
    ADD COLUMN atm_dispensed             numeric(12,2),
    ADD COLUMN atm_fee                   numeric(12,2),
    ADD CONSTRAINT ck_money_day_entry_atm_trans CHECK (coalesce(atm_trans, 0) >= 0);

ALTER TABLE report.money_day_entry_change DROP CONSTRAINT ck_money_day_entry_change_field;
ALTER TABLE report.money_day_entry_change ADD CONSTRAINT ck_money_day_entry_change_field CHECK (field IN (
    'cash_deposit', 'check_deposit', 'card_batch', 'generic_card_1', 'generic_card_2', 'ebt', 'cash_purchases',
    'cash_expenses', 'general_account_paid', 'other_income', 'cust_account_sales', 'cust_account_payments',
    'general_account_received', 'atm_trans', 'atm_dispensed', 'atm_fee'));
