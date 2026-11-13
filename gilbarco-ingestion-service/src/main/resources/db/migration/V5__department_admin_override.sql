-- =====================================================================================
-- V5: a platform admin can set a department's name and sales class.
-- The loader names a department only from a department ring; scanned lines carry just the number. A
-- department that is only ever scanned (scratch-off tickets) stays unnamed and falls into merchandise
-- (Wolf, 2026-10-14..15: dept 5, $2,527.00 of NY scratch-off games). An admin fixes that once:
--
--   dept_name / sales_class  what every report uses (unchanged meaning: the report views read them)
--   pos_name                 the name the POS files last gave (kept up to date by the loader, always)
--   admin_set_by / _at       set: the admin owns dept_name and sales_class; the loader never changes them
--
-- master.department_changes records every admin change (it moves money between merchandise and lottery
-- on every report, past days included).
-- =====================================================================================
ALTER TABLE master.departments
    ADD COLUMN pos_name      text,
    ADD COLUMN admin_set_by  bigint,
    ADD COLUMN admin_set_at  timestamptz,
    ADD CONSTRAINT ck_master_departments_admin CHECK ((admin_set_by IS NULL) = (admin_set_at IS NULL));

UPDATE master.departments SET pos_name = dept_name;

CREATE TABLE master.department_changes (
    change_id        bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    store_id         bigint       NOT NULL REFERENCES master.stores (store_id) ON DELETE RESTRICT,
    dept_number      integer      NOT NULL,
    action           text         NOT NULL,                 -- SET: admin set name/class; RESET: back to the POS name
    old_name         text,
    old_sales_class  text         NOT NULL,
    new_name         text,
    new_sales_class  text         NOT NULL,
    note             text,
    changed_by       bigint       NOT NULL,                 -- hor-auth account id
    changed_at       timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_master_department_changes_action CHECK (action IN ('SET', 'RESET'))
);
CREATE INDEX ix_master_department_changes ON master.department_changes (store_id, dept_number, changed_at DESC);
