package com.handsoffretail.gilbarco.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.Test;

/** The bucket naming rules of DAY_PACKAGES_Zip.md section 2, for any file type. */
class GilbarcoKeysTest {

    private static final LocalDate DAY = LocalDate.parse("2026-09-24");

    @Test
    void foldersAndVersions() {
        assertThat(GilbarcoKeys.monthName(YearMonth.of(2026, 9))).isEqualTo("september");
        assertThat(GilbarcoKeys.isMonthFolder("September/", YearMonth.of(2026, 9))).isTrue();
        assertThat(GilbarcoKeys.dayTypeFolder(DAY, "PJR")).isEqualTo("2026/september/2026-09-24/PJR/");
        assertThat(GilbarcoKeys.isTypeFolder("pjr/", "PJR")).isTrue();
        assertThat(GilbarcoKeys.isTypeFolder("PJRX/", "PJR")).isFalse();

        assertThat(GilbarcoKeys.version("wolf_800_NY-13208_PJR_2026-09-24_v12.zip", DAY, "PJR")).contains(12);
        assertThat(GilbarcoKeys.version("wolf_800_NY-13208_MCM_2026-09-24_v1.zip", DAY, "PJR")).isEmpty();
        assertThat(GilbarcoKeys.version("wolf_800_NY-13208_PJR_2026-09-25_v1.zip", DAY, "PJR")).isEmpty();
        assertThat(GilbarcoKeys.version("wolf_800_NY-13208_PJR_2026-09-24_v0.zip", DAY, "PJR")).isEmpty();
    }

    @Test
    void theDayOfAKeyNeedsTheSameDateInFolderAndName() {
        assertThat(GilbarcoKeys.dayOf("2026-09-24/PJR/wolf_PJR_2026-09-24_v1.zip", "PJR")).contains(DAY);
        assertThat(GilbarcoKeys.dayOf("2026-09-24/PJR/wolf_PJR_2026-09-23_v1.zip", "PJR")).isEmpty();
        assertThat(GilbarcoKeys.dayOf("2026-09-24/MCM/wolf_MCM_2026-09-24_v1.zip", "PJR")).isEmpty();
    }

    @Test
    void theHighestVersionWinsAndTiesAreAmbiguous() {
        ZipObject v9 = zip("a_PJR_2026-09-24_v9.zip", 9);
        ZipObject v10 = zip("a_PJR_2026-09-24_v10.zip", 10);
        assertThat(GilbarcoKeys.latest(List.of(v9, v10))).contains(v10);
        assertThatThrownBy(() -> GilbarcoKeys.latest(List.of(v10, zip("b_PJR_2026-09-24_v10.zip", 10))))
                .isInstanceOf(ZipSourceException.class);
    }

    @Test
    void aFileTypeCannotInjectAPattern() {
        assertThatThrownBy(() -> GilbarcoKeys.version("x.zip", DAY, ".*")).isInstanceOf(IllegalArgumentException.class);
    }

    private static ZipObject zip(String name, int version) {
        return new ZipObject("f/2026/september/2026-09-24/PJR/" + name, "e", 1, Instant.EPOCH, DAY, "PJR", version);
    }
}
