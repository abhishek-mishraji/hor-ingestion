package com.handsoffretail.verifone.verify;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** The second pass on small synthetic files. */
class CountReconcilerTest {

    @Test
    void theCustomerCopyOfAnAgeCheckIsNotCountedTwice() throws Exception {
        String age = "<custDOB dob=\"021481\" custAge=\"45\" minAge=\"21\" dobEntryMeth=\"Manual\">021481</custDOB>";
        String xml = "<transSet><trans type=\"sale\"><trHeader><customer>" + age + "</customer></trHeader>"
                + "<trValue>" + age + "</trValue></trans>"
                + "<trans type=\"sale\"><trHeader/><trValue>" + age + "</trValue></trans></transSet>";

        Map<String, Long> counts = new CountReconciler().count(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        assertThat(counts).containsEntry(CountReconciler.AGE_CHECKS, 2L).containsEntry("trans[sale]", 2L);
    }
}
