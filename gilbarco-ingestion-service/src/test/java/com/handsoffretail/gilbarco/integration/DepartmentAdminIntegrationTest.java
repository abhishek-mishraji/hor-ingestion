package com.handsoffretail.gilbarco.integration;

import static com.handsoffretail.gilbarco.filetypes.pjr.PjrXml.item;
import static com.handsoffretail.gilbarco.filetypes.pjr.PjrXml.journal;
import static com.handsoffretail.gilbarco.filetypes.pjr.PjrXml.ring;
import static com.handsoffretail.gilbarco.filetypes.pjr.PjrXml.tender;
import static com.handsoffretail.gilbarco.filetypes.pjr.PjrXml.transaction;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import com.handsoffretail.gilbarco.IngestionIntegrationTest;
import com.handsoffretail.gilbarco.TestBucket;
import com.handsoffretail.gilbarco.TestStores;
import com.handsoffretail.gilbarco.TestTokens;
import com.handsoffretail.gilbarco.fetch.FetchRequest;
import com.handsoffretail.gilbarco.filetypes.pjr.PjrXml;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A platform admin names a department the POS files never name (scratch-offs are only scanned), the reports follow
 * at once, a later file cannot undo it, and returning it to the POS name works; every change is recorded.
 * Uses its own department number (91) and days, so the shared Wolf rows of other tests are untouched.
 */
class DepartmentAdminIntegrationTest extends IngestionIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String DEPTS = "/api/ingestion/gilbarco/stores/" + TestStores.WOLF + "/departments";
    private static final int DEPT = 91;

    @LocalServerPort
    private int port;

    @Test
    void anAdminNamesAScannedOnlyDepartmentAndTheFilesCannotUndoIt() {
        LocalDate scannedDay = LocalDate.parse("2025-02-10");
        putPjrDay(scannedDay, 1, List.of(scannedScratchOff(scannedDay, 1)));
        assertThat(fetch(scannedDay, FetchRequest.Mode.REPLACE).status()).isEqualTo(FetchRequest.Status.LOADED);

        JsonNode unnamed = department();
        assertThat(absent(unnamed.path("deptName"))).isTrue();
        assertThat(unnamed.path("salesClass").asString()).isEqualTo("merchandise");
        assertThat(unnamed.path("adminManaged").asBoolean()).isFalse();
        // The other test of this class may have loaded a later day of the same department.
        assertThat(LocalDate.parse(unnamed.path("lastSold").asString())).isAfterOrEqualTo(scannedDay);

        // What was sold tells the admin what the department is.
        JsonNode sold = call(HttpMethod.GET, DEPTS + "/" + DEPT + "/items?limit=5", null, admin(), false).body();
        assertThat(sold.path("items").valueStream().filter(i -> i.path("posCode").asString().equals("71443301701")))
                .singleElement().satisfies(i -> {
                    assertThat(i.path("description").asString()).isEqualTo("CASHWORD");
                    assertThat(i.path("lineType").asString()).isEqualTo("ItemLine");
                    // The other test of this class may sell the same item on another day.
                    assertThat(i.path("lines").asLong()).isBetween(1L, 2L);
                    assertThat(i.path("amount").decimalValue()).isEqualByComparingTo(
                            new java.math.BigDecimal("5.00").multiply(java.math.BigDecimal.valueOf(i.path("lines").asLong())));
                });
        assertThat(sold.path("lines").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(sold.path("distinctItems").asLong()).isGreaterThanOrEqualTo(1);

        Response set = call(HttpMethod.PUT, DEPTS + "/" + DEPT,
                body("LOTTO SCRATCH OFF", "lottery_sale", "scratch tickets are only scanned"), admin(), true);
        assertThat(set.status()).as(set.body().toString()).isEqualTo(200);
        assertThat(set.body().path("deptName").asString()).isEqualTo("LOTTO SCRATCH OFF");
        assertThat(set.body().path("salesClass").asString()).isEqualTo("lottery_sale");
        assertThat(set.body().path("adminManaged").asBoolean()).isTrue();
        assertThat(set.body().path("adminSetBy").asLong()).isEqualTo(TestStores.ADMIN_ID);
        assertThat(reportClass(scannedDay)).isEqualTo("lottery_sale");

        // The same values again change nothing and record nothing.
        assertThat(call(HttpMethod.PUT, DEPTS + "/" + DEPT, body("LOTTO SCRATCH OFF", "lottery_sale", null), admin(), true)
                .status()).isEqualTo(200);

        // A later file names the department on the register: the admin's values stay, pos_name follows the file.
        LocalDate ringDay = LocalDate.parse("2025-02-11");
        putPjrDay(ringDay, 1, List.of(rungAs(ringDay, 1, "REGISTER NAME")));
        assertThat(fetch(ringDay, FetchRequest.Mode.REPLACE).status()).isEqualTo(FetchRequest.Status.LOADED);
        JsonNode kept = department();
        assertThat(kept.path("deptName").asString()).isEqualTo("LOTTO SCRATCH OFF");
        assertThat(kept.path("salesClass").asString()).isEqualTo("lottery_sale");
        assertThat(kept.path("posName").asString()).isEqualTo("REGISTER NAME");
        assertThat(reportClass(ringDay)).isEqualTo("lottery_sale");

        Response reset = call(HttpMethod.DELETE, DEPTS + "/" + DEPT + "/override", null, admin(), true);
        assertThat(reset.status()).isEqualTo(200);
        assertThat(reset.body().path("deptName").asString()).isEqualTo("REGISTER NAME");
        assertThat(reset.body().path("salesClass").asString()).isEqualTo("merchandise");
        assertThat(reset.body().path("adminManaged").asBoolean()).isFalse();
        assertThat(reportClass(scannedDay)).isEqualTo("merchandise");

        JsonNode changes = call(HttpMethod.GET, DEPTS + "/" + DEPT + "/changes", null, admin(), false).body();
        assertThat(changes.valueStream().map(c -> c.path("action").asString()).toList()).containsExactly("RESET", "SET");
        JsonNode first = changes.get(1);
        assertThat(absent(first.path("oldName"))).isTrue();
        assertThat(first.path("oldSalesClass").asString()).isEqualTo("merchandise");
        assertThat(first.path("newSalesClass").asString()).isEqualTo("lottery_sale");
        assertThat(first.path("note").asString()).isEqualTo("scratch tickets are only scanned");
        assertThat(first.path("changedBy").asLong()).isEqualTo(TestStores.ADMIN_ID);
    }

    @Test
    void onlyAPlatformAdminWithTheCsrfHeaderMayChangeADepartmentAndBadInputIsRefused() {
        LocalDate day = LocalDate.parse("2025-02-12");
        putPjrDay(day, 1, List.of(scannedScratchOff(day, 1)));
        assertThat(fetch(day, FetchRequest.Mode.REPLACE).status()).isEqualTo(FetchRequest.Status.LOADED);
        String path = DEPTS + "/" + DEPT;
        Map<String, Object> good = body("X", "lottery_sale", null);

        assertThat(call(HttpMethod.PUT, path, good, admin(), false).status()).isEqualTo(403);
        assertThat(call(HttpMethod.PUT, path, good, TestTokens.clientUser(5, List.of(TestStores.WOLF)), true).status())
                .isEqualTo(403);
        assertThat(call(HttpMethod.PUT, path, good, null, true).status()).isEqualTo(401);

        assertProblem(call(HttpMethod.PUT, path, body("X", "fuel", null), admin(), true), 422, "BAD_DEPARTMENT");
        assertProblem(call(HttpMethod.PUT, path, body("   ", "merchandise", null), admin(), true), 422, "BAD_DEPARTMENT");
        assertProblem(call(HttpMethod.PUT, path, body("X".repeat(61), "merchandise", null), admin(), true), 422,
                "BAD_DEPARTMENT");
        assertProblem(call(HttpMethod.PUT, path, Map.of("name", "X"), admin(), true), 400, "VALIDATION_FAILED");
        assertProblem(call(HttpMethod.PUT, DEPTS + "/999", good, admin(), true), 404, "NOT_FOUND");
        assertProblem(call(HttpMethod.PUT, "/api/ingestion/gilbarco/stores/" + TestStores.VERIFONE_STORE + "/departments/"
                + DEPT, good, admin(), true), 404, "NOT_FOUND");
        assertProblem(call(HttpMethod.GET, DEPTS + "/999/changes", null, admin(), false), 404, "NOT_FOUND");
        assertProblem(call(HttpMethod.GET, DEPTS + "/999/items", null, admin(), false), 404, "NOT_FOUND");
        assertProblem(call(HttpMethod.GET, path + "/items?limit=0", null, admin(), false), 422, "BAD_LIMIT");
        assertProblem(call(HttpMethod.GET, path + "/items?limit=101", null, admin(), false), 422, "BAD_LIMIT");
    }

    private JsonNode department() {
        JsonNode all = call(HttpMethod.GET, DEPTS, null, admin(), false).body();
        return all.valueStream().filter(d -> d.path("deptNumber").asInt() == DEPT).findFirst()
                .orElseThrow(() -> new AssertionError("department " + DEPT + " not listed"));
    }

    private String reportClass(LocalDate day) {
        return jdbc.queryForObject("""
                SELECT sales_class FROM report.rpt_department_daily
                WHERE store_id = ? AND business_date = ? AND dept_number = ?""", String.class, TestStores.WOLF, day, DEPT);
    }

    /** A scanned item only carries the department number, so the department stays unnamed. */
    private static TestBucket.Entry scannedScratchOff(LocalDate day, int n) {
        return TestBucket.Entry.of(PjrXml.fileName(day, n), journal(day, transaction("SaleEvent", day, 2, n, false, false,
                "5.00", "Collected", item("normal", DEPT, "7144330170" + n, "CASHWORD", "5.00", "1", "5.00", null),
                tender("cash", "generic", "5.00"))));
    }

    /** A department ring names the department. */
    private static TestBucket.Entry rungAs(LocalDate day, int n, String name) {
        return TestBucket.Entry.of(PjrXml.fileName(day, n), journal(day, transaction("SaleEvent", day, 2, n, false, false,
                "5", "Collected", ring("normal", DEPT, name, "1", "5"), tender("cash", "generic", "5"))));
    }

    private static Map<String, Object> body(String name, String salesClass, String note) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("salesClass", salesClass);
        if (note != null) {
            body.put("note", note);
        }
        return body;
    }

    /** The service leaves null fields out of its JSON. */
    private static boolean absent(JsonNode node) {
        return node.isMissingNode() || node.isNull();
    }

    private static void assertProblem(Response response, int status, String code) {
        assertThat(response.status()).as(response.body().toString()).isEqualTo(status);
        assertThat(response.body().path("code").asString()).isEqualTo(code);
    }

    private static String admin() {
        return TestTokens.platformAdmin(TestStores.ADMIN_ID);
    }

    private Response call(HttpMethod method, String path, Object body, String token, boolean csrf) {
        RestClient.RequestBodySpec request = RestClient.create("http://localhost:" + port).method(method).uri(path);
        if (token != null) {
            request.header(HttpHeaders.COOKIE, TestTokens.COOKIE + "=" + token);
        }
        if (csrf) {
            request.header("X-HOR-Request", "1").header(HttpHeaders.ORIGIN, TestTokens.ORIGIN);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).body(JSON.writeValueAsString(body));
        }
        return request.exchange((req, res) -> {
            byte[] bytes = res.getBody().readAllBytes();
            return new Response(res.getStatusCode().value(), bytes.length == 0 ? JSON.nullNode() : JSON.readTree(bytes));
        });
    }

    private record Response(int status, JsonNode body) {
    }
}
