package com.handsoffretail.gilbarco.filetypes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.handsoffretail.auth.client.DomainException;
import com.handsoffretail.gilbarco.common.GilbarcoErrorCode;
import com.handsoffretail.gilbarco.verify.LoadCheck;

/** The registry is the only way to a file type: a new type plugs in as one more bean (open/closed). */
class FileTypeRegistryTest {

    @Test
    void typesAreFoundIgnoringCaseAndPjrIsTheDefault() {
        FileTypeRegistry registry = new FileTypeRegistry(List.of(stub("PJR"), stub("FGM")));

        assertThat(registry.require("pjr").fileType()).isEqualTo("PJR");
        assertThat(registry.require(null).fileType()).isEqualTo("PJR");
        assertThat(registry.require(" fgm ").fileType()).isEqualTo("FGM");
        assertThat(registry.supported()).containsExactly("FGM", "PJR");
    }

    @Test
    void anUnsupportedTypeIs422() {
        FileTypeRegistry registry = new FileTypeRegistry(List.of(stub("PJR")));

        assertThatThrownBy(() -> registry.require("MSM")).isInstanceOfSatisfying(DomainException.class,
                e -> assertThat(e.getMessage()).contains("MSM"))
                .extracting(e -> ((DomainException) e).errorCode()).isEqualTo(GilbarcoErrorCode.UNSUPPORTED_FILE_TYPE);
    }

    @Test
    void twoProcessorsForOneTypeAreAMistake() {
        assertThatThrownBy(() -> new FileTypeRegistry(List.of(stub("PJR"), stub("pjr")))).isInstanceOf(IllegalStateException.class);
    }

    private static FileTypeProcessor stub(String type) {
        return new FileTypeProcessor() {
            @Override
            public String fileType() {
                return type;
            }

            @Override
            public LoadedDays days() {
                throw new UnsupportedOperationException();
            }

            @Override
            public LoadOutcome processDay(FileLoadContext context) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<LoadCheck> supportedChecks() {
                return List.of();
            }
        };
    }
}
