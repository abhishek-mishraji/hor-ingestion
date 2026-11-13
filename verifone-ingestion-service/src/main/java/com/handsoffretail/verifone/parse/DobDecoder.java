package com.handsoffretail.verifone.parse;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.Period;

/**
 * custDOB/@dob is MMDDYY (it matched the POS's custAge on all sample records). The century is the
 * one that makes the age on the ticket day equal @custAge; when none does, the load fails
 * (IMPLEMENTATION_PLAN.md Phase 4). Messages never contain the birth date.
 */
public final class DobDecoder {

    private static final int[] CENTURIES = {2000, 1900};
    private static final int MMDDYY_LENGTH = 6;

    private DobDecoder() {
    }

    public static LocalDate decode(String mmddyy, int customerAge, LocalDate ticketDay, String path) {
        if (mmddyy == null || mmddyy.length() != MMDDYY_LENGTH || !mmddyy.chars().allMatch(Character::isDigit)) {
            throw new CpjFormatException("custDOB/@dob is not MMDDYY at " + path);
        }
        int month = Integer.parseInt(mmddyy.substring(0, 2));
        int day = Integer.parseInt(mmddyy.substring(2, 4));
        int year = Integer.parseInt(mmddyy.substring(4, 6));
        for (int century : CENTURIES) {
            try {
                LocalDate birth = LocalDate.of(century + year, month, day);
                if (!birth.isAfter(ticketDay) && Period.between(birth, ticketDay).getYears() == customerAge) {
                    return birth;
                }
            } catch (DateTimeException notADate) {
                throw new CpjFormatException("custDOB/@dob is not a calendar date at " + path, notADate);
            }
        }
        throw new CpjFormatException("no century of custDOB/@dob gives the POS age " + customerAge + " at " + path);
    }
}
