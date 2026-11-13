package com.handsoffretail.verifone.load;

import static java.sql.Types.BIGINT;
import static java.sql.Types.BOOLEAN;
import static java.sql.Types.DATE;
import static java.sql.Types.INTEGER;
import static java.sql.Types.NUMERIC;
import static java.sql.Types.SMALLINT;
import static java.sql.Types.TIMESTAMP_WITH_TIMEZONE;
import static java.sql.Types.VARCHAR;

import java.util.ArrayList;
import java.util.List;

/** The cpj tables a day is written to, listed in foreign-key order (parents before children). */
final class CpjTables {

    static final Table FILE_TOTALS = table("cpj.file_totals", false, "cpj_file_id", BIGINT, "totals_kind", VARCHAR,
            "inside_sales", NUMERIC, "inside_grand", NUMERIC, "outside_sales", NUMERIC, "outside_grand", NUMERIC,
            "attendant_inside_sales", NUMERIC, "attendant_inside_grand", NUMERIC, "attendant_outside_sales", NUMERIC,
            "attendant_outside_grand", NUMERIC, "overall_sales", NUMERIC, "overall_grand", NUMERIC);
    static final Table CASHIER_SESSIONS = table("cpj.cashier_sessions", false, "cpj_file_id", BIGINT, "register_id", BIGINT,
            "cashier_version_id", BIGINT, "cashier_period", INTEGER, "drawer", INTEGER, "opened_at", TIMESTAMP_WITH_TIMEZONE,
            "begin_cash", NUMERIC, "begin_cash_mop_code", INTEGER, "begin_cash_mop_cat", INTEGER,
            "closed_at", TIMESTAMP_WITH_TIMEZONE, "close_begin_cash", NUMERIC, "close_begin_food_stamp", NUMERIC,
            "begin_ticket", BIGINT, "end_ticket", BIGINT, "ticket_count", INTEGER, "mws_enabled", BOOLEAN);
    static final Table PERIOD_CLOSES = table("cpj.period_closes", false, "cpj_file_id", BIGINT, "period_type", INTEGER,
            "period_seq", INTEGER, "period_name", VARCHAR, "closed_at", TIMESTAMP_WITH_TIMEZONE, "unique_id", VARCHAR,
            "mws_enabled", BOOLEAN);
    static final Table TICKETS = table("cpj.tickets", true, "ticket_id", BIGINT, "cpj_file_id", BIGINT, "seq_in_file", INTEGER,
            "trans_type", VARCHAR, "is_suspended", BOOLEAN, "is_fuel_prepay", BOOLEAN, "is_rollback", BOOLEAN,
            "is_prepay_completion", BOOLEAN, "is_recalled", BOOLEAN, "register_id", BIGINT, "tr_seq", BIGINT,
            "term_msg_type", VARCHAR, "terminal_no", INTEGER, "term_msg_sn", BIGINT, "unique_id", VARCHAR, "unique_sn", BIGINT,
            "ticket_at", TIMESTAMP_WITH_TIMEZONE, "duration_sec", INTEGER, "hour_seq", INTEGER, "shift_seq", INTEGER,
            "day_seq", INTEGER, "till", INTEGER, "orig_register_id", BIGINT, "orig_tr_seq", BIGINT, "recall_trans_type", VARCHAR,
            "recall_register_id", BIGINT, "recall_tr_seq", BIGINT, "misc_ref", VARCHAR, "house_account_version_id", BIGINT,
            "is_fuel_only_customer", BOOLEAN, "currency", VARCHAR, "total", NUMERIC, "total_no_tax", NUMERIC,
            "total_tax", NUMERIC, "total_with_tax", NUMERIC, "s_totalizer", NUMERIC, "g_totalizer", NUMERIC,
            "recall_amount", NUMERIC);
    static final Table TICKET_CASHIERS = table("cpj.ticket_cashiers", false, "ticket_id", BIGINT, "cashier_role", VARCHAR,
            "cashier_version_id", BIGINT, "register_id", BIGINT, "cashier_period", INTEGER, "drawer", INTEGER);
    static final Table TICKET_FOOD_STAMPS = table("cpj.ticket_food_stamps", false, "ticket_id", BIGINT, "total", NUMERIC,
            "tax", NUMERIC, "change_amount", NUMERIC, "tendered", NUMERIC);
    static final Table TICKET_AGE_CHECKS = table("cpj.ticket_age_checks", false, "ticket_id", BIGINT, "birth_date", DATE,
            "customer_age", SMALLINT, "min_age", SMALLINT, "entry_method", VARCHAR);
    static final Table TICKET_TAXES = table("cpj.ticket_taxes", false, "ticket_id", BIGINT, "tax_version_id", BIGINT,
            "is_refund_tax", BOOLEAN, "taxable_amount", NUMERIC, "tax_rate", NUMERIC, "tax_amount", NUMERIC,
            "tax_attribute", VARCHAR, "exempt_amount", NUMERIC, "exempt_against_amount", NUMERIC);
    static final Table TICKET_COUPONS = table("cpj.ticket_coupons", false, "ticket_id", BIGINT, "coupon_no", SMALLINT,
            "discount_amount", NUMERIC, "affects_tax", BOOLEAN, "entry_method", VARCHAR, "coupon_data", VARCHAR);
    static final Table TICKET_NETWORK_PRODUCTS = table("cpj.ticket_network_products", false, "ticket_id", BIGINT,
            "entry_no", SMALLINT, "product_code", VARCHAR, "item_count", INTEGER, "amount", NUMERIC);
    static final Table TICKET_LOYALTY = table("cpj.ticket_loyalty", false, "ticket_id", BIGINT, "loyalty_program_id", BIGINT,
            "loyalty_account_id", BIGINT, "auth_reply", VARCHAR, "auto_discount", NUMERIC, "customer_discount", NUMERIC,
            "entry_method", VARCHAR, "subtotal", NUMERIC);
    static final Table PAYMENT_LINES = table("cpj.payment_lines", true, "payment_line_id", BIGINT, "ticket_id", BIGINT,
            "line_no", SMALLINT, "payline_type", VARCHAR, "payment_type_version_id", BIGINT, "amount", NUMERIC,
            "currency", VARCHAR, "pos_sysid", INTEGER, "is_duplicate", BOOLEAN, "is_pending", BOOLEAN,
            "original_preauth_amount", NUMERIC, "coupon_entry_method", VARCHAR);
    static final Table PAYMENT_CARD_DETAILS = table("cpj.payment_card_details", false, "payment_line_id", BIGINT,
            "card_id", BIGINT, "card_type_id", BIGINT, "card_name_sysid", INTEGER, "entry_method", VARCHAR,
            "auth_code", VARCHAR, "auth_at", TIMESTAMP_WITH_TIMEZONE, "auth_source", VARCHAR, "batch_no", VARCHAR,
            "acquirer_batch_no", VARCHAR, "host_id", VARCHAR, "reference_no", VARCHAR, "sequence_no", VARCHAR,
            "host_ticket", VARCHAR, "is_above_site", BOOLEAN);
    static final Table CASHIER_EVENTS = table("cpj.cashier_events", true, "cashier_event_id", BIGINT, "cpj_file_id", BIGINT,
            "seq_in_file", INTEGER, "event_type", VARCHAR, "event_at", TIMESTAMP_WITH_TIMEZONE, "register_id", BIGINT,
            "cashier_version_id", BIGINT, "cashier_entry_mode", VARCHAR, "term_msg_sn", BIGINT, "unique_id", VARCHAR,
            "event_text", VARCHAR, "item_description", VARCHAR, "from_quantity", NUMERIC, "to_quantity", NUMERIC,
            "duration_sec", INTEGER, "hour_seq", INTEGER, "shift_seq", INTEGER, "day_seq", INTEGER, "term_msg_type", VARCHAR,
            "terminal_no", INTEGER, "cashier_period", INTEGER, "drawer", INTEGER);
    static final Table EVENT_DRAWER_TOTALS = table("cpj.cashier_event_drawer_totals", false, "cashier_event_id", BIGINT,
            "tender_name", VARCHAR, "amount", NUMERIC);
    static final Table EVENT_PRICE_CHECKS = table("cpj.cashier_event_price_checks", false, "cashier_event_id", BIGINT,
            "item_version_id", BIGINT, "quantity", NUMERIC, "unit_price", NUMERIC, "line_total", NUMERIC);
    static final Table TICKET_LINES = table("cpj.ticket_lines", true, "line_id", BIGINT, "ticket_id", BIGINT,
            "cashier_event_id", BIGINT, "line_no", SMALLINT, "line_type", VARCHAR, "is_duplicate", BOOLEAN,
            "is_unsettled", BOOLEAN, "department_version_id", BIGINT, "category_version_id", BIGINT,
            "item_version_id", BIGINT, "upc_entry_method", VARCHAR, "network_code", VARCHAR, "quantity", NUMERIC,
            "sign", SMALLINT, "sell_unit", NUMERIC, "unit_price", NUMERIC, "line_total", NUMERIC, "price_override", NUMERIC,
            "discount_type", VARCHAR, "discount_value", NUMERIC, "voided_line_no", SMALLINT, "pos_description", VARCHAR);
    static final Table LINE_FLAGS = table("cpj.line_flags", false, "line_id", BIGINT, "flag_code", VARCHAR, "cause", VARCHAR);
    static final Table LINE_FUEL = table("cpj.line_fuel", false, "line_id", BIGINT, "fuel_type", VARCHAR,
            "fuel_grade_version_id", BIGINT, "fuel_price_level_version_id", BIGINT, "fuel_service_mode_version_id", BIGINT,
            "fuel_position", SMALLINT, "volume", NUMERIC, "volume_uom", VARCHAR, "base_price", NUMERIC,
            "deposit_amount", NUMERIC, "fuel_seq", BIGINT);
    static final Table LINE_FUEL_DISCOUNTS = table("cpj.line_fuel_discounts", false, "line_id", BIGINT,
            "loyalty_program_id", BIGINT, "discount_per_gallon", NUMERIC, "original_price", NUMERIC, "total_discount", NUMERIC,
            "volume", NUMERIC);
    static final Table LINE_LOYALTY_DISCOUNTS = table("cpj.line_loyalty_discounts", false, "line_id", BIGINT,
            "loyalty_program_id", BIGINT, "discount_amount", NUMERIC, "quantity", NUMERIC, "tax_credit", NUMERIC);
    static final Table LINE_PROMOTIONS = table("cpj.line_promotions", false, "line_id", BIGINT, "match_no", SMALLINT,
            "promotion_version_id", BIGINT, "mixes", INTEGER, "match_quantity", NUMERIC, "match_price", NUMERIC,
            "promo_amount", NUMERIC);
    static final Table LINE_TAXES = table("cpj.line_taxes", false, "line_id", BIGINT, "tax_version_id", BIGINT,
            "tax_amount", NUMERIC, "tax_rate", NUMERIC, "is_reverse", BOOLEAN);
    static final Table FUEL_PRICE_CHANGES = table("cpj.fuel_price_changes", false, "ticket_id", BIGINT, "changed_by", VARCHAR,
            "change_origin", VARCHAR);
    static final Table FUEL_PRICE_CHANGE_LINES = table("cpj.fuel_price_change_lines", false, "ticket_id", BIGINT,
            "entry_no", SMALLINT, "info_sysid", INTEGER, "fuel_grade_id", BIGINT, "fuel_service_mode_id", BIGINT,
            "fuel_price_level_id", BIGINT, "fuel_tier_id", BIGINT, "previous_price", NUMERIC, "previous_price_sysid", INTEGER,
            "current_price", NUMERIC, "current_price_sysid", INTEGER);

    /** Flush order: every table after the tables its foreign keys point to. */
    static final List<Table> FLUSH_ORDER = List.of(FILE_TOTALS, CASHIER_SESSIONS, PERIOD_CLOSES, TICKETS, TICKET_CASHIERS,
            TICKET_FOOD_STAMPS, TICKET_AGE_CHECKS, TICKET_TAXES, TICKET_COUPONS, TICKET_NETWORK_PRODUCTS, TICKET_LOYALTY,
            PAYMENT_LINES, PAYMENT_CARD_DETAILS, CASHIER_EVENTS, EVENT_DRAWER_TOTALS, EVENT_PRICE_CHECKS, TICKET_LINES,
            LINE_FLAGS, LINE_FUEL, LINE_FUEL_DISCOUNTS, LINE_LOYALTY_DISCOUNTS, LINE_PROMOTIONS, LINE_TAXES,
            FUEL_PRICE_CHANGES, FUEL_PRICE_CHANGE_LINES);

    private CpjTables() {
    }

    private static Table table(String name, boolean overridingId, Object... nameTypePairs) {
        List<Table.Column> columns = new ArrayList<>();
        for (int i = 0; i < nameTypePairs.length; i += 2) {
            columns.add(new Table.Column((String) nameTypePairs[i], (Integer) nameTypePairs[i + 1]));
        }
        return new Table(name, columns, overridingId);
    }
}
