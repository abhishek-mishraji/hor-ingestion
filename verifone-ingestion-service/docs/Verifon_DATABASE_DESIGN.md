# Verifone Ingestion Database Design (`verifone_db`)

**Service:** `verifone-ingestion-service` (Port **8092**)  
**Database:** `verifone_db` (PostgreSQL 16/17, UTF-8, UTC)  
**Database Role:** `verifone_ingest_svc`  
**Master Authority for Stores:** `auth_db.core.stores` (synced to `master.stores`)  
**Date:** 2026-10-20  

---

## 1. Architectural Tree Hierarchy

To prevent table clutter and allow independent lifecycles for different POS report formats (CPJ, MSM, FGM), `verifone_db` is partitioned into dedicated schemas:

```
                            verifone_db (Database)
                                       │
        ┌──────────────────────────────┴──────────────────────────────┐
        │                                                             │
 ┌──────┴──────┐                                               ┌──────┴──────┐
 │   master    │  (Shared store replica & POS master codes)    │   ingest    │  (S3 zip audit & fetch status)
 │   schema    │  departments, items, cashiers, fuel_grades    │   schema    │  file_type='CPJ'|'MSM'|'FGM'
 └──────┬──────┘                                               └──────┬──────┘
        │                                                             │
        │   ┌──────────────────────────┼──────────────────────────┐   │
        │   │                          │                          │   │
        ▼   ▼                          ▼   ▼                      ▼   ▼
 ┌───────────────┐              ┌───────────────┐          ┌───────────────┐
 │  cpj schema   │              │  msm schema   │          │  fgm schema   │
 │ (Active now)  │              │   (Future)    │          │   (Future)    │
 ├───────────────┤              ├───────────────┤          ├───────────────┤
 │• cpj_files    │              │• msm_files    │          │• fgm_files    │
 │• file_totals  │              │• item_sales   │          │• fuel_meters  │
 │• tickets      │              │• dept_totals  │          │• tank_dips    │
 │• ticket_lines │              │• hourly_sales │          │• deliveries   │
 │• payment_lines│              │• ...          │          │• blend_meters │
 │• cashier_evts │              │               │          │               │
 └───────────────┘              └───────────────┘          └───────────────┘
```

---

## 2. Where the `stores` Table is Stored

The store directory is stored in:

$$\mathbf{verifone\_db.master.stores}$$

### 2.1 Table Definition
```sql
CREATE TABLE master.stores (
    store_id     bigint       PRIMARY KEY,              -- equals auth_db core.stores.store_id
    store_code   text         NOT NULL UNIQUE,         -- e.g. 8730_IN-47872
    store_name   text         NOT NULL,                -- e.g. Tiki Hut
    pos_vendor   text         NOT NULL,                -- VERIFONE
    s3_folder    text         NOT NULL,                -- Tiki_8730_IN-47872/Tiki_8730_IN-47872/
    timezone     text         NOT NULL,                -- America/Indiana/Indianapolis
    status       text         NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    synced_at    timestamptz  NOT NULL DEFAULT now()
);
```

### 2.2 Why `master.stores` Exists Here:
1. **True SQL Foreign Keys:** Every transaction table in `cpj.*`, `msm.*`, and `fgm.*` has a real SQL `FOREIGN KEY REFERENCES master.stores(store_id)`.
2. **Autonomous Daily Ingestion:** Daily transaction extraction reads **only** from `master.stores`. If `auth_db` is ever offline, daily ingestion runs without interruption.
3. **Synchronization API:** The sync lives on **this service** (verifone-ingestion-service, port 8092), not on
   hor-auth. When a store is added, edited, or deactivated in `auth_db`, a platform admin clicks "Sync Stores" or invokes:
   ```http
   POST /api/ingestion/verifone/stores/sync
   X-HOR-Request: 1
   ```
   (PLATFORM_ADMIN only; cookie `hor_access`; allowed `Origin`). In one transaction on `verifone_db` it reads the
   `pos_vendor = 'VERIFONE'` rows of `auth_db.core.stores` and: inserts new store ids; updates name, folder, timezone
   and status of known ids with the same `store_code`; reports a conflict (and changes nothing) when a known id has a
   different `store_code` or a code already belongs to another id; marks rows that are no longer VERIFONE (or are gone)
   `INACTIVE`. Rows are never deleted. Response: `200 {inserted, updated, deactivated, unchanged, conflicts}`.
   `auth_db` is read only during a sync; fetches and all reads use `master.stores`
   ([VERIFONE_SERVICE_PLAN.md](VERIFONE_SERVICE_PLAN.md) §6).

---

## 3. Complete Table & View Inventory by Schema

### 3.1 `master` Schema (28 Tables - Reference & Store Data)
Shared master reference data and versioned POS entity history:

| Table Name | Description |
|---|---|
| `master.stores` | Local store replica (`store_id`, `store_code`, `store_name`, `s3_folder`, `timezone`, `status`, `synced_at`) |
| `master.departments` | Store department IDs & lottery class assignments (`LOTTERY_SALE`, `LOTTERY_PAYOUT`, or null) |
| `master.department_versions` | Department name history as written by POS (e.g., "Grocery", "Lotto") |
| `master.categories` | Merchandise category IDs per store |
| `master.category_versions` | Category names history |
| `master.items` | UPC product numbers and UPC modifiers |
| `master.item_versions` | Item descriptions as written by POS (e.g., "Marlboro Gold 100s") |
| `master.cashiers` | Cashier system IDs |
| `master.cashier_versions` | Cashier employee numbers and names |
| `master.registers` | Register/terminal numbers (`pos_num`, physical register IDs) |
| `master.taxes` | Tax system IDs per store |
| `master.tax_versions` | Tax names and historical percentages/rates |
| `master.payment_types` | Method of Payment codes (`mop_code`, paycode e.g. "CASH", "DEBIT", "CREDIT") |
| `master.payment_type_versions` | NACS tender codes, sub-codes, and tender categories |
| `master.card_types` | Card brands (e.g., "VISA", "Mastercard", "Amex", "Discover") |
| `master.cards` | Masked card numbers only (Never full PAN: e.g. `************1234`) |
| `master.loyalty_programs` | Loyalty program identifiers |
| `master.loyalty_accounts` | Masked customer loyalty account numbers |
| `master.fuel_grades` | Fuel grade IDs (e.g., Regular, Plus, Premium, Diesel) |
| `master.fuel_grade_versions` | Fuel grade names and NAXML grade identifiers |
| `master.fuel_price_levels` | Fuel price level IDs (Cash vs Credit) |
| `master.fuel_price_level_versions` | Price level names |
| `master.fuel_service_modes` | Service mode IDs (Self-serve vs Full-serve) |
| `master.fuel_service_mode_versions` | Service mode names |
| `master.fuel_tiers` | Tier system IDs |
| `master.promotions` | Promotion and deal codes |
| `master.promotion_versions` | Promotion names and offer types (e.g., mixAndMatchOffer) |
| `master.house_accounts` | Commercial fleet / house account IDs |
| `master.house_account_versions` | House account names |

---

### 3.2 `ingest` Schema (3 Tables - S3 Download Audit)
Tracks physical file lifecycle from AWS S3 bucket `hor-stores-data`:

| Table Name | Description |
|---|---|
| `ingest.fetch_requests` | Audit log of fetch attempts (`RUNNING`, `LOADED`, `REFUSED`, `NOT_FOUND`, `FAILED`, duration, actor) |
| `ingest.s3_objects` | Downloaded S3 zip metadata (bucket, object key, ETag, size, version number, `file_type`) |
| `ingest.s3_object_files` | Individual files inside each zip with verified SHA-256 checksums |

---

### 3.3 `cpj` Schema (28 Tables - Closed Period Journal Transactions)
Normalized transaction data from Closed Period Journal XML files (closed business days):

| Table Name | Description |
|---|---|
| `cpj.cpj_files` | Root day header for one closed business day (period ID, site ID, opened/closed times) |
| `cpj.file_totals` | Cumulative start & end POS meter readings (inside/outside grand sales) |
| `cpj.cashier_sessions` | Register drawer sessions (open/close cash, drawer ID, ticket count) |
| `cpj.period_closes` | Shift and day close events |
| `cpj.tickets` | Individual customer transaction headers (ticket #, terminal #, total, tax, flags) |
| `cpj.ticket_cashiers` | Cashier assigned to ticket (CASHIER or ORIGINAL cashier on refund/recalled) |
| `cpj.ticket_age_checks` | Age verification audit (customer age, birth date) |
| `cpj.ticket_food_stamps` | EBT / food stamp payments, taxes, and change |
| `cpj.ticket_taxes` | Ticket-level tax breakdown (taxable amount, tax rate, tax amount) |
| `cpj.ticket_coupons` | Manufacturer/store coupons applied to ticket |
| `cpj.ticket_network_products` | Network product transaction codes and amounts |
| `cpj.ticket_loyalty` | Loyalty discounts and authorization replies on ticket |
| `cpj.payment_lines` | Individual tender lines on ticket (sale vs change) |
| `cpj.payment_card_details` | Auth codes, batch #, sequence #, card brand for card tenders |
| `cpj.fuel_price_changes` | Price change event header |
| `cpj.fuel_price_change_lines` | Price change detail per grade, price level, and tier |
| `cpj.cashier_events` | Non-sale register actions (safe drops, payouts, drawer opens, price checks) |
| `cpj.cashier_event_drawer_totals` | Drawer totals per tender during drawer events |
| `cpj.cashier_event_price_checks` | Item price check events |
| `cpj.ticket_lines` | Every line item (merchandise UPC sale, fuel sale, void, lottery) |
| `cpj.line_flag_types` | Lookup of POS line flag codes (`trlPLU`, `trlFuelSale`, `trlNetVoid`, etc.) |
| `cpj.line_flags` | Specific flags attached to each ticket line |
| `cpj.line_fuel` | Fuel transaction details (pump number, volume gallons, base price, deposit) |
| `cpj.line_fuel_discounts` | Cents-per-gallon discounts applied per loyalty program |
| `cpj.line_loyalty_discounts` | Loyalty discounts applied to specific items |
| `cpj.line_promotions` | Promotion / mix-and-match discounts applied to lines |
| `cpj.line_taxes` | Specific tax applied to a line item |
| `cpj.load_checks` | Verification audit of 9 financial balance checks executed during load |

---

### 3.4 `report` Schema (16 Analytical Views)
Pre-aggregated analytical views built on top of `cpj` and `master`:

| View Name | Description |
|---|---|
| `report.sale_tickets` | Clean customer sales tickets excluding voids |
| `report.sale_lines` | All merchandise & fuel lines with department names |
| `report.ticket_list` | High-level summary of transactions for receipts |
| `report.payments_daily` | Daily breakdown of tenders (Cash vs Card vs EBT) |
| `report.cash_movements` | Safe drops, pay-ins, and pay-outs |
| `report.suspicious_events` | Error corrects, high voids, and refunds for audit |
| `report.daily_summary` | Daily executive totals (gross sales, net sales, taxes, fuel gallons) |
| `report.department_daily` | Sales and quantity totals by department per day |
| `report.department_monthly` | Monthly department rollups |
| `report.items_daily` | Top selling UPC items per day |
| `report.lottery_daily` | Lottery sales vs lottery payouts per day |
| `report.lottery_monthly` | Monthly lottery reconciliation |
| `report.fuel_daily` | Gallons and revenue per fuel grade per day |
| `report.fuel_monthly` | Monthly fuel rollups |
| `report.age_group_sales_daily` | Tobacco & alcohol sales grouped by customer age |
| `report.loaded_days` | Catalog of all business dates successfully loaded |

---

### 3.5 `msm` Schema (Future - Merchandise Sales Movement)
- `msm.msm_files`: Merchandise movement file header.
- `msm.department_sales`: Summary sales per department.
- `msm.item_sales`: Quantities and dollar volumes per UPC.
- `msm.hourly_sales`: Sales broken down by hour.

---

### 3.6 `fgm` Schema (Future - Fuel Grade Movement)
- `fgm.fgm_files`: Fuel movement file header.
- `fgm.tank_dips`: Opening and closing physical tank levels.
- `fgm.fuel_meters`: Pump totalizer movements and blend ratios.
- `fgm.fuel_deliveries`: Bulk fuel delivery receipts.

---

## 4. Referential Integrity & Deletion Rules

| Parent | Child | Action on Delete | Rationale |
|---|---|---|---|
| `master.stores` | `cpj.cpj_files` | `RESTRICT` | Cannot delete a store that has loaded financial data. |
| `master.*_versions` | `cpj.ticket_lines` | `RESTRICT` | Master codes cannot be deleted while referenced by tickets. |
| `cpj.cpj_files` | `cpj.tickets` | `CASCADE` | Enables clean `REPLACE` reload of a business day. |
| `cpj.tickets` | `cpj.ticket_lines` | `CASCADE` | Removing a ticket removes its line items. |
| `cpj.tickets` | `cpj.payment_lines` | `CASCADE` | Removing a ticket removes its payments. |
