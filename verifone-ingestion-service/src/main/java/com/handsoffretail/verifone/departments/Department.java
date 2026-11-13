package com.handsoffretail.verifone.departments;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A store's department with its names over time and its lottery class. The class is fixed by the
 * department's name in the database (V10, master.lottery_class_for): LOTTO / LOTTERY = lottery, and
 * PAYOUT in the name or a negative department = payout. Tiki Hut: 29 Lotto = LOTTERY_SALE,
 * 28 Lotto Payout = LOTTERY_PAYOUT.
 */
public record Department(int deptNumber, String currentName, List<Name> names, Optional<LotteryClass> lotteryClass) {

    public Department {
        Objects.requireNonNull(currentName, "currentName");
        names = List.copyOf(names);
        Objects.requireNonNull(lotteryClass, "lotteryClass");
    }

    /** ck_departments_lottery. */
    public enum LotteryClass {
        LOTTERY_SALE,
        LOTTERY_PAYOUT
    }

    /** One name the department had, and the first / last loaded day a line used it (empty: no loaded line). */
    public record Name(String name, String deptType, Optional<LocalDate> firstSeen, Optional<LocalDate> lastSeen) {
    }
}
