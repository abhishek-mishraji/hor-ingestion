package com.handsoffretail.verifone.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.Test;

class CpjKeysTest {

    private static final LocalDate DAY = LocalDate.parse("2026-10-16");

    @Test
    void monthFoldersMatchIgnoringCase() {
        assertThat(CpjKeys.monthName(YearMonth.of(2026, 10))).isEqualTo("october");
        assertThat(CpjKeys.isMonthFolder("October/", YearMonth.of(2026, 10))).isTrue();
        assertThat(CpjKeys.isMonthFolder("OCTOBER/", YearMonth.of(2026, 10))).isTrue();
        assertThat(CpjKeys.isMonthFolder("november/", YearMonth.of(2026, 10))).isFalse();
    }

    @Test
    void versionsAreReadOnlyFromCpjZipsOfThatDay() {
        assertThat(CpjKeys.version("Tiki_8730_IN-47872_CPJ_2026-10-16_v17.zip", DAY)).contains(17);
        assertThat(CpjKeys.version("Tiki_8730_IN-47872_PJR_2026-10-16_v17.zip", DAY)).isEmpty();
        assertThat(CpjKeys.version("Tiki_8730_IN-47872_CPJ_2026-10-15_v1.zip", DAY)).isEmpty();
        assertThat(CpjKeys.version("Tiki_8730_IN-47872_CPJ_2026-10-16_v1.zip.part", DAY)).isEmpty();
    }

    @Test
    void theHighestVersionIsComparedAsANumber() {
        List<ZipObject> zips = List.of(zip(2), zip(10), zip(9));

        assertThat(CpjKeys.latest(zips)).hasValueSatisfying(z -> assertThat(z.versionNo()).isEqualTo(10));
        assertThat(CpjKeys.latest(List.of())).isEmpty();
    }

    @Test
    void dayOfReadsAKeyInsideItsMonthFolder() {
        assertThat(CpjKeys.dayOf("2026-10-16/CPJ/Tiki_8730_IN-47872_CPJ_2026-10-16_v1.zip")).contains(DAY);
        assertThat(CpjKeys.dayOf("2026-10-16/cpj/x_CPJ_2026-10-16_v3.zip")).contains(DAY);
        assertThat(CpjKeys.dayOf("2026-10-16/PJR/x_PJR_2026-10-16_v3.zip")).isEmpty();
        assertThat(CpjKeys.dayOf("2026-10-16/CPJ/x_CPJ_2026-10-15_v3.zip")).isEmpty();
    }

    private static ZipObject zip(int version) {
        return new ZipObject("k/x_CPJ_2026-10-16_v" + version + ".zip", "e" + version, 1, Instant.EPOCH, DAY, version);
    }
}
