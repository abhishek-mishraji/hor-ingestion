package com.handsoffretail.gilbarco.filetypes.mcm;

import static com.handsoffretail.gilbarco.filetypes.mcm.McmXml.detail;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.handsoffretail.gilbarco.TestEnv;
import com.handsoffretail.gilbarco.filetypes.mcm.parser.McmFormatException;
import com.handsoffretail.gilbarco.filetypes.mcm.parser.McmParser;
import com.handsoffretail.gilbarco.filetypes.mcm.parser.McmReport;

class McmParserTest {

    private static final LocalDate DAY = LocalDate.parse("2026-10-24");
    private final McmParser parser = new McmParser();

    @Test
    void theStoreLevelFileGivesOneRowPerDepartmentAsWritten() {
        McmReport r = parse(McmXml.store(DAY,
                detail(1, "Grocery", "235.23", "118", "-2.22", "0", "0", 0),
                detail(56, "Food Truck", "193", "7", "0", "0", "-126", 1),
                detail(1024, "Fuel 1", "6523.64", "239", "0", "-.62", "0", 0)));

        assertThat(r.primaryPeriod()).isEqualTo(McmReport.STORE_PERIOD);
        assertThat(r.businessDate()).isEqualTo(DAY);
        assertThat(r.periodBegin()).isEqualTo(LocalDateTime.parse("2026-10-24T00:00:51"));
        assertThat(r.periodEnd()).isEqualTo(LocalDateTime.parse("2026-10-25T00:11:36"));
        assertThat(r.storeLocation()).isEqualTo("13");
        assertThat(r.departments()).extracting(McmReport.Department::deptNumber).containsExactly(1, 56, 1024);
        McmReport.Department fuel = r.departments().get(2);
        assertThat(fuel.deptName()).isEqualTo("Fuel 1");
        assertThat(fuel.discountAmount()).isEqualByComparingTo("-0.62");
        assertThat(r.departments().get(1).refundAmount()).isEqualByComparingTo("-126");
        assertThat(r.departments().get(1).refundCount()).isEqualTo(1);
        assertThat(r.departments().get(0).promotionAmount()).isEqualByComparingTo("-2.22");
    }

    @Test
    void thePerRegisterFileIsReadForItsHeaderOnly() {
        McmReport r = parse(McmXml.registers(DAY, detail(1, "Grocery", "48.26", "30")));

        assertThat(r.primaryPeriod()).isEqualTo(McmReport.REGISTER_PERIOD);
        assertThat(r.businessDate()).isEqualTo(DAY);
        assertThat(r.departments()).isEmpty();
    }

    @Test
    void anythingTheRulesDoNotExplainIsRefusedNamingTheFile() {
        assertRefused(McmXml.period(5, DAY, detail(1, "Grocery", "1", "1")), "unknown PrimaryReportPeriod 5");
        assertRefused(McmXml.store(DAY, detail(1, "A", "1", "1"), detail(1, "B", "2", "1")), "MerchandiseCode 1 listed twice");
        assertRefused(McmXml.store(DAY, detail(1, "A", "1", "1")).replace("<SalesAmount>1</SalesAmount>", ""),
                "no SalesAmount");
        assertRefused(McmXml.store(DAY, detail(1, "A", "x", "1")), "NumberFormatException");
        assertRefused(McmXml.store(DAY).replace("MerchandiseCodeMovement", "ItemSalesMovement"), "no MerchandiseCodeMovement");
        assertRefused("<NAXML-POSJournal/>", "root element is NAXML-POSJournal");
        assertRefused("<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><x>&e;</x>",
                "not valid XML");
    }

    /** The real Wolf store-level MCM of 2026-10-24 (from the Passport file study), when present: StoreClose "Sales". */
    @Test
    void theRealStoreFileOf20261024TotalsTheStoreClose() throws IOException {
        Optional<Path> file = realStoreFile();
        Assumptions.assumeTrue(file.isPresent(), "Wolf/passport-file-study MCM of 2026-10-24 not found: skipped");
        McmReport r;
        try (InputStream in = Files.newInputStream(file.get())) {
            r = parser.parse(in, file.get().getFileName().toString());
        }

        assertThat(r.primaryPeriod()).isEqualTo(2);
        assertThat(r.departments()).hasSize(22).allSatisfy(d -> assertThat(d.deptName()).isNotBlank());
        assertThat(r.departments().stream().map(McmReport.Department::salesAmount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("10587.90");
        assertThat(r.departments()).filteredOn(d -> d.deptNumber() == 4).singleElement()
                .extracting(McmReport.Department::deptName).isEqualTo("Cigarettes");
    }

    private McmReport parse(String xml) {
        return parser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.ISO_8859_1)), "MCM1.xml");
    }

    private void assertRefused(String xml, String message) {
        assertThatThrownBy(() -> parse(xml)).isInstanceOf(McmFormatException.class).hasMessageStartingWith("MCM1.xml: ")
                .hasMessageContaining(message);
    }

    private static Optional<Path> realStoreFile() throws IOException {
        Optional<Path> dir = TestEnv.get("HOR_TESTDATA_ROOT").filter(v -> !v.isBlank())
                .map(v -> Path.of(v, "Wolf", "passport-file-study", "raw", "2026-10-24", "MCM")).filter(Files::isDirectory);
        if (dir.isEmpty()) {
            return Optional.empty();
        }
        try (Stream<Path> walk = Files.walk(dir.get())) {
            List<Path> files = walk.filter(p -> p.getFileName().toString().equals("MCM3402610250014071598846.xml")).toList();
            return files.stream().findFirst();
        }
    }
}
