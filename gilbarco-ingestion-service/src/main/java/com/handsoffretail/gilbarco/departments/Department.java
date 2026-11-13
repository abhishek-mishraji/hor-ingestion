package com.handsoffretail.gilbarco.departments;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A store's department. {@code deptName} and {@code salesClass} are what the reports use. Normally they follow the
 * POS files: the name from a department ring ({@code posName}) and the class from the name (master.sales_class_for):
 * LOTTO / LOTTERY = lottery_sale, with PAID OUT / PAYOUT = lottery_payout, otherwise merchandise. When a platform
 * admin set them ({@code adminSetBy} / {@code adminSetAt}), the admin's values stay whatever the files say (V5).
 * {@code lastSold} is the last loaded business day with a line in this department.
 */
public record Department(int deptNumber, Optional<String> deptName, Optional<String> posName, String salesClass,
        Optional<Long> adminSetBy, Optional<Instant> adminSetAt, Optional<LocalDate> lastSold, Instant updatedAt) {

    /** The classes an admin may choose. Fuel is not a department choice: fuel lines are classified by line type. */
    public static final Set<String> SETTABLE_CLASSES = Set.of("merchandise", "lottery_sale", "lottery_payout");

    public static final int MAX_NAME = 60;
    public static final int MAX_NOTE = 500;

    public Department {
        Objects.requireNonNull(deptName, "deptName");
        Objects.requireNonNull(posName, "posName");
        Objects.requireNonNull(salesClass, "salesClass");
        Objects.requireNonNull(adminSetBy, "adminSetBy");
        Objects.requireNonNull(adminSetAt, "adminSetAt");
        Objects.requireNonNull(lastSold, "lastSold");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public boolean adminManaged() {
        return adminSetAt.isPresent();
    }

    /**
     * One item sold in the department (not cancelled), over all loaded days: what an admin looks at to name it.
     * {@code posCode} is the UPC / PLU of a scanned item; a department ring has none.
     */
    public record Item(Optional<String> posCode, Optional<String> description, String lineType, BigDecimal units,
            BigDecimal amount, long lines, LocalDate lastSold) {
    }

    /** The most sold items (by lines) and the totals over every item of the department. */
    public record Items(List<Item> items, long distinctItems, long lines, BigDecimal amount) {

        public Items {
            items = List.copyOf(items);
        }
    }

    /** One admin change: SET (name and class chosen) or RESET (back to the POS name and its class). */
    public record Change(long changeId, String action, Optional<String> oldName, String oldSalesClass,
            Optional<String> newName, String newSalesClass, Optional<String> note, long changedBy, Instant changedAt) {
    }
}
