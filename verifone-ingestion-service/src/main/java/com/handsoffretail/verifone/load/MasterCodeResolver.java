package com.handsoffretail.verifone.load;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;

import com.handsoffretail.verifone.parse.model.Codes;

/**
 * Turns the codes a file names into {@code master.*} ids for one store, with a per-load cache (all
 * of the store's existing codes are read once at the start). A missing code or version is inserted
 * with {@code INSERT ... ON CONFLICT DO NOTHING} and then read, which is safe when two loads add
 * the same code at the same moment.
 */
final class MasterCodeResolver {

    // code tables: (table, id column, key columns); version tables: (table, id column, parent column, value columns)
    static final Code DEPARTMENTS = new Code("master.departments", "department_id", "dept_number");
    static final Version DEPARTMENT_VERSIONS = new Version("master.department_versions", "department_version_id", "department_id", "name", "dept_type");
    static final Code CATEGORIES = new Code("master.categories", "category_id", "category_number");
    static final Version CATEGORY_VERSIONS = new Version("master.category_versions", "category_version_id", "category_id", "name");
    static final Code ITEMS = new Code("master.items", "item_id", "upc", "upc_modifier");
    static final Version ITEM_VERSIONS = new Version("master.item_versions", "item_version_id", "item_id", "description");
    static final Code CASHIERS = new Code("master.cashiers", "cashier_id", "cashier_sysid");
    static final Version CASHIER_VERSIONS = new Version("master.cashier_versions", "cashier_version_id", "cashier_id", "name", "employee_number");
    static final Code REGISTERS = new Code("master.registers", "register_id", "pos_num");
    static final Code TAXES = new Code("master.taxes", "tax_id", "tax_sysid");
    static final Version TAX_VERSIONS = new Version("master.tax_versions", "tax_version_id", "tax_id", "name");
    static final Code PAYMENT_TYPES = new Code("master.payment_types", "payment_type_id", "mop_code", "paycode");
    static final Version PAYMENT_TYPE_VERSIONS = new Version("master.payment_type_versions", "payment_type_version_id",
            "payment_type_id", "mop_category", "nacs_tender_code", "nacs_tender_sub");
    static final Code CARD_TYPES = new Code("master.card_types", "card_type_id", "card_name");
    static final Code CARDS = new Code("master.cards", "card_id", "masked_number");
    static final Code LOYALTY_PROGRAMS = new Code("master.loyalty_programs", "loyalty_program_id", "program_code");
    static final Version LOYALTY_ACCOUNTS = new Version("master.loyalty_accounts", "loyalty_account_id", "loyalty_program_id", "account_number");
    static final Code FUEL_GRADES = new Code("master.fuel_grades", "fuel_grade_id", "grade_sysid");
    static final Version FUEL_GRADE_VERSIONS = new Version("master.fuel_grade_versions", "fuel_grade_version_id", "fuel_grade_id", "name", "naxml_grade_id");
    static final Code FUEL_PRICE_LEVELS = new Code("master.fuel_price_levels", "fuel_price_level_id", "level_sysid");
    static final Version FUEL_PRICE_LEVEL_VERSIONS = new Version("master.fuel_price_level_versions", "fuel_price_level_version_id", "fuel_price_level_id", "name");
    static final Code FUEL_SERVICE_MODES = new Code("master.fuel_service_modes", "fuel_service_mode_id", "mode_sysid");
    static final Version FUEL_SERVICE_MODE_VERSIONS = new Version("master.fuel_service_mode_versions", "fuel_service_mode_version_id", "fuel_service_mode_id", "name");
    static final Code FUEL_TIERS = new Code("master.fuel_tiers", "fuel_tier_id", "tier_sysid");
    static final Code PROMOTIONS = new Code("master.promotions", "promotion_id", "promotion_code");
    static final Version PROMOTION_VERSIONS = new Version("master.promotion_versions", "promotion_version_id", "promotion_id", "name", "promo_type");
    static final Code HOUSE_ACCOUNTS = new Code("master.house_accounts", "house_account_id", "account_number");
    static final Version HOUSE_ACCOUNT_VERSIONS = new Version("master.house_account_versions", "house_account_version_id", "house_account_id", "name");

    private static final List<Code> CODES = List.of(DEPARTMENTS, CATEGORIES, ITEMS, CASHIERS, REGISTERS, TAXES,
            PAYMENT_TYPES, CARD_TYPES, CARDS, LOYALTY_PROGRAMS, FUEL_GRADES, FUEL_PRICE_LEVELS, FUEL_SERVICE_MODES,
            FUEL_TIERS, PROMOTIONS, HOUSE_ACCOUNTS);
    private static final Map<Version, Code> VERSIONS = Map.ofEntries(Map.entry(DEPARTMENT_VERSIONS, DEPARTMENTS),
            Map.entry(CATEGORY_VERSIONS, CATEGORIES), Map.entry(ITEM_VERSIONS, ITEMS),
            Map.entry(CASHIER_VERSIONS, CASHIERS), Map.entry(TAX_VERSIONS, TAXES),
            Map.entry(PAYMENT_TYPE_VERSIONS, PAYMENT_TYPES), Map.entry(LOYALTY_ACCOUNTS, LOYALTY_PROGRAMS),
            Map.entry(FUEL_GRADE_VERSIONS, FUEL_GRADES), Map.entry(FUEL_PRICE_LEVEL_VERSIONS, FUEL_PRICE_LEVELS),
            Map.entry(FUEL_SERVICE_MODE_VERSIONS, FUEL_SERVICE_MODES), Map.entry(PROMOTION_VERSIONS, PROMOTIONS),
            Map.entry(HOUSE_ACCOUNT_VERSIONS, HOUSE_ACCOUNTS));

    private final JdbcTemplate jdbc;
    private final long storeId;
    private final Map<Object, Map<List<Object>, Long>> cache = new HashMap<>();

    MasterCodeResolver(JdbcTemplate jdbc, long storeId) {
        this.jdbc = jdbc;
        this.storeId = storeId;
        preload();
    }

    // ---- typed lookups ------------------------------------------------------------------------

    long departmentVersion(Codes.Department d) {
        return version(DEPARTMENT_VERSIONS, code(DEPARTMENTS, d.number()), d.name(), d.type());
    }

    long categoryVersion(Codes.Category c) {
        return version(CATEGORY_VERSIONS, code(CATEGORIES, c.number()), c.name());
    }

    long itemVersion(Codes.Item i) {
        return version(ITEM_VERSIONS, code(ITEMS, i.upc(), i.modifier()), i.description());
    }

    long cashierVersion(Codes.Cashier c) {
        return version(CASHIER_VERSIONS, code(CASHIERS, c.sysid()), c.name(), c.employeeNumber().orElse(null));
    }

    long register(int posNum) {
        return code(REGISTERS, posNum);
    }

    long taxVersion(Codes.Tax t) {
        return version(TAX_VERSIONS, code(TAXES, t.sysid()), t.name());
    }

    long paymentTypeVersion(Codes.PaymentType p) {
        return version(PAYMENT_TYPE_VERSIONS, code(PAYMENT_TYPES, p.mop(), p.paycode()), p.category(),
                p.nacsTenderCode(), p.nacsTenderSub());
    }

    long cardType(String name) {
        return code(CARD_TYPES, name);
    }

    long card(String maskedNumber) {
        return code(CARDS, maskedNumber);
    }

    long loyaltyProgram(String programCode) {
        return code(LOYALTY_PROGRAMS, programCode);
    }

    Optional<Long> loyaltyAccount(long programId, Optional<String> account) {
        return account.map(a -> version(LOYALTY_ACCOUNTS, programId, a));
    }

    long fuelGradeVersion(Codes.FuelGrade g) {
        return version(FUEL_GRADE_VERSIONS, fuelGrade(g.sysid()), g.name(), g.naxmlGradeId().orElse(null));
    }

    long fuelGrade(int sysid) {
        return code(FUEL_GRADES, sysid);
    }

    long priceLevelVersion(Codes.NamedCode level) {
        return version(FUEL_PRICE_LEVEL_VERSIONS, priceLevel(level.sysid()), level.name());
    }

    long priceLevel(int sysid) {
        return code(FUEL_PRICE_LEVELS, sysid);
    }

    long serviceModeVersion(Codes.NamedCode mode) {
        return version(FUEL_SERVICE_MODE_VERSIONS, serviceMode(mode.sysid()), mode.name());
    }

    long serviceMode(int sysid) {
        return code(FUEL_SERVICE_MODES, sysid);
    }

    long fuelTier(int sysid) {
        return code(FUEL_TIERS, sysid);
    }

    long promotionVersion(Codes.Promotion p) {
        return version(PROMOTION_VERSIONS, code(PROMOTIONS, p.code()), p.name(), p.promoType());
    }

    long houseAccountVersion(Codes.HouseAccount h) {
        return version(HOUSE_ACCOUNT_VERSIONS, code(HOUSE_ACCOUNTS, h.accountNumber()), h.name());
    }

    // ---- generic upsert-and-get --------------------------------------------------------------

    private long code(Code table, Object... key) {
        List<Object> values = Collections.unmodifiableList(Arrays.asList(key));
        return cache.computeIfAbsent(table, t -> new HashMap<>()).computeIfAbsent(values, v -> insertCode(table, v));
    }

    private long version(Version table, long parentId, Object... values) {
        List<Object> key = new ArrayList<>();
        key.add(parentId);
        key.addAll(Arrays.asList(values));
        List<Object> cacheKey = Collections.unmodifiableList(key);
        return cache.computeIfAbsent(table, t -> new HashMap<>()).computeIfAbsent(cacheKey, k -> insertVersion(table, k));
    }

    private long insertCode(Code t, List<Object> key) {
        List<Object> args = new ArrayList<>();
        args.add(storeId);
        args.addAll(key);
        String columns = "store_id, " + String.join(", ", t.keys());
        List<Long> inserted = jdbc.queryForList("INSERT INTO " + t.table() + " (" + columns + ") VALUES ("
                + marks(args.size()) + ") ON CONFLICT DO NOTHING RETURNING " + t.id(), Long.class, args.toArray());
        if (!inserted.isEmpty()) {
            return inserted.get(0);
        }
        return jdbc.queryForObject("SELECT " + t.id() + " FROM " + t.table() + " WHERE store_id = ? AND "
                + equalities(t.keys()), Long.class, args.toArray());
    }

    private long insertVersion(Version t, List<Object> key) {
        List<String> columns = new ArrayList<>();
        columns.add(t.parent());
        columns.addAll(t.values());
        List<Long> inserted = jdbc.queryForList("INSERT INTO " + t.table() + " (" + String.join(", ", columns)
                + ") VALUES (" + marks(key.size()) + ") ON CONFLICT DO NOTHING RETURNING " + t.id(), Long.class, key.toArray());
        if (!inserted.isEmpty()) {
            return inserted.get(0);
        }
        return jdbc.queryForObject("SELECT " + t.id() + " FROM " + t.table() + " WHERE " + equalities(columns),
                Long.class, key.toArray());
    }

    /** All codes and versions of the store, so most lookups need no database round trip. */
    private void preload() {
        for (Code t : CODES) {
            Map<List<Object>, Long> ids = cache.computeIfAbsent(t, x -> new HashMap<>());
            jdbc.query("SELECT " + t.id() + ", " + String.join(", ", t.keys()) + " FROM " + t.table() + " WHERE store_id = ?",
                    (ResultSet rs) -> {
                        ids.put(row(rs, 2, t.keys().size() + 1), rs.getLong(1));
                    }, storeId);
        }
        VERSIONS.forEach((v, c) -> {
            Map<List<Object>, Long> ids = cache.computeIfAbsent(v, x -> new HashMap<>());
            String columns = String.join(", ", v.values().stream().map(col -> "v." + col).toList());
            jdbc.query("SELECT v." + v.id() + ", v." + v.parent() + ", " + columns + " FROM " + v.table() + " v JOIN "
                    + c.table() + " c ON c." + c.id() + " = v." + v.parent() + " WHERE c.store_id = ?",
                    (ResultSet rs) -> {
                        ids.put(row(rs, 2, v.values().size() + 2), rs.getLong(1));
                    }, storeId);
        });
    }

    private static List<Object> row(ResultSet rs, int from, int to) throws SQLException {
        List<Object> values = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            values.add(rs.getObject(i));
        }
        return Collections.unmodifiableList(values);
    }

    private static String marks(int n) {
        return String.join(", ", Collections.nCopies(n, "?"));
    }

    /** {@code a IS NOT DISTINCT FROM ?}: a NULL employee number or NAXML id matches NULL. */
    private static String equalities(List<String> columns) {
        return String.join(" AND ", columns.stream().map(c -> c + " IS NOT DISTINCT FROM ?").toList());
    }

    record Code(String table, String id, List<String> keys) {
        Code(String table, String id, String... keys) {
            this(table, id, List.of(keys));
        }
    }

    record Version(String table, String id, String parent, List<String> values) {
        Version(String table, String id, String parent, String... values) {
            this(table, id, parent, List.of(values));
        }
    }
}
