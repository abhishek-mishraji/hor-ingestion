package com.handsoffretail.gilbarco.integration;

import static com.handsoffretail.gilbarco.filetypes.pjr.PjrXml.item;
import static com.handsoffretail.gilbarco.filetypes.pjr.PjrXml.journal;
import static com.handsoffretail.gilbarco.filetypes.pjr.PjrXml.ring;
import static com.handsoffretail.gilbarco.filetypes.pjr.PjrXml.tender;
import static com.handsoffretail.gilbarco.filetypes.pjr.PjrXml.transaction;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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
 * The HTTP API over real HTTP with hor-auth cookie tokens and the CSRF header, and the fetch flow's end states:
 * LOADED, REFUSED, NOT_FOUND and FAILED (bad file, wrong business date, corrupt zip), on synthetic Wolf days.
 */
class GilbarcoIngestionIntegrationTest extends IngestionIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String BASE = "/api/ingestion/gilbarco";

    @LocalServerPort
    private int port;

    @Test
    void everyEndpointNeedsAPlatformAdmin() {
        assertThat(call(HttpMethod.GET, BASE + "/fetches", null, null).status()).isEqualTo(401);
        Response client = call(HttpMethod.GET, BASE + "/fetches", null, TestTokens.clientUser(5, List.of(TestStores.WOLF)));
        assertThat(client.status()).isEqualTo(403);
        assertThat(call(HttpMethod.GET, BASE + "/stores/999/departments", null, admin()).status()).isEqualTo(404);
        assertThat(call(HttpMethod.GET, "/actuator/health", null, admin()).status()).isIn(403, 404);
    }

    @Test
    void writesNeedTheCsrfHeaderAndAnAllowedOrigin() {
        Response noHeader = call(HttpMethod.POST, BASE + "/stores/sync", null, admin(), false);
        assertThat(noHeader.status()).isEqualTo(403);
        assertThat(noHeader.body().path("code").asString()).isEqualTo("CSRF_REJECTED");
        Response sync = call(HttpMethod.POST, BASE + "/stores/sync", null, admin());
        assertThat(sync.status()).isEqualTo(200);
        assertThat(sync.body().path("conflicts").isArray()).isTrue();
    }

    @Test
    void storesListsOnlySyncedGilbarcoStores() {
        JsonNode stores = call(HttpMethod.GET, BASE + "/stores", null, admin()).body();
        List<String> codes = stores.valueStream().map(s -> s.path("storeCode").asString()).toList();
        assertThat(codes).contains(TestBucket.STORE_CODE, "CLOSED_GB").doesNotContain("8730_IN-47872");
    }

    @Test
    void theSupportedFileTypesAreListed() {
        JsonNode types = call(HttpMethod.GET, BASE + "/file-types", null, admin()).body();
        assertThat(types.valueStream().map(t -> t.path("fileType").asString() + ":" + t.path("isDefault").asBoolean()).toList())
                .containsExactly("MCM:false", "PJR:true");
    }

    @Test
    void fetchThroughTheApiThenReadDetailDaysAndDepartments() {
        LocalDate day = LocalDate.parse("2025-01-20");
        putPjrDay(day, 1, List.of(sale(day, 1), lottoSale(day, 2)));

        Response started = call(HttpMethod.POST, BASE + "/fetches",
                Map.of("storeId", TestStores.WOLF, "businessDate", day.toString(), "mode", "REPLACE", "fileType", "pjr"), admin());
        assertThat(started.status()).isEqualTo(202);
        long id = started.body().path("fetchRequestId").asLong();

        JsonNode detail = awaitDetail(id);
        assertThat(detail.path("fetch").path("status").asString()).as(detail.toString()).isEqualTo("LOADED");
        assertThat(detail.path("fetch").path("fileType").asString()).isEqualTo("PJR");
        assertThat(detail.path("zip").path("version").asInt()).isEqualTo(1);
        assertThat(detail.path("zip").path("files").asLong()).isEqualTo(2);
        assertThat(detail.path("rows").path("pjr.event").asLong()).isEqualTo(2);
        assertThat(detail.path("checks").valueStream().filter(c -> c.path("hard").asBoolean())
                .allMatch(c -> c.path("passed").asBoolean())).isTrue();

        String store = BASE + "/stores/" + TestStores.WOLF;
        JsonNode available = call(HttpMethod.GET, store + "/available-days?year=2025&month=1", null, admin()).body();
        assertThat(available.valueStream().map(JsonNode::asString).toList()).contains("2025-01-20");
        JsonNode loaded = call(HttpMethod.GET, store + "/loaded-days?from=2025-01-01&to=2025-01-31&fileType=PJR", null, admin()).body();
        assertThat(loaded.valueStream().filter(d -> d.path("businessDate").asString().equals("2025-01-20")))
                .singleElement().satisfies(d -> assertThat(d.path("files").asLong()).isEqualTo(2));

        JsonNode departments = call(HttpMethod.GET, store + "/departments", null, admin()).body();
        assertThat(departments.valueStream().filter(d -> d.path("deptNumber").asInt() == 57)).singleElement()
                .satisfies(d -> {
                    assertThat(d.path("deptName").asString()).isEqualTo("LOTTO ONLINE");
                    assertThat(d.path("salesClass").asString()).isEqualTo("lottery_sale");
                });

        JsonNode history = call(HttpMethod.GET, BASE + "/fetches?storeId=" + TestStores.WOLF + "&fileType=PJR&limit=50", null,
                admin()).body();
        assertThat(history.path("items").valueStream().map(f -> f.path("id").asLong()).toList()).contains(id);
    }

    @Test
    void malformedRequestsAre400AndRuleBreaksAre4xxWithACode() {
        Response bad = call(HttpMethod.POST, BASE + "/fetches", Map.of("storeId", TestStores.WOLF, "mode", "REPLACE"), admin());
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.body().path("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertProblem(start(TestStores.WOLF, "2099-01-01", "PJR"), 422, "DATE_IN_FUTURE");
        assertProblem(start(TestStores.WOLF, "2025-01-20", "MSM"), 422, "UNSUPPORTED_FILE_TYPE");
        assertProblem(start(TestStores.INACTIVE_STORE, "2025-01-20", "PJR"), 409, "STORE_INACTIVE");
        assertProblem(start(TestStores.VERIFONE_STORE, "2025-01-20", "PJR"), 404, "NOT_FOUND");
        Response range = call(HttpMethod.GET, BASE + "/stores/" + TestStores.WOLF + "/loaded-days?from=2025-02-01&to=2025-01-01",
                null, admin());
        assertProblem(range, 422, "BAD_RANGE");
    }

    @Test
    void oneFileTheRulesDoNotExplainFailsTheWholeDayAndIsRecorded() {
        LocalDate day = LocalDate.parse("2025-01-21");
        String badName = PjrXml.fileName(day, 2);
        putPjrDay(day, 1, List.of(sale(day, 1), TestBucket.Entry.of(badName,
                journal(day, "<FooEvent><BusinessDate>" + day + "</BusinessDate></FooEvent>"))));

        FetchRequest failed = fetch(day, FetchRequest.Mode.REPLACE);

        assertThat(failed.status()).isEqualTo(FetchRequest.Status.FAILED);
        assertThat(failed.message()).hasValueSatisfying(m -> assertThat(m).contains(badName, "unknown event type FooEvent"));
        assertThat(count("SELECT count(*) FROM pjr.pjr_file WHERE store_id = ? AND business_date = ?", TestStores.WOLF, day)).isZero();
        assertThat(count("SELECT count(*) FROM ingest.s3_objects WHERE store_id = ? AND business_date = ?", TestStores.WOLF, day))
                .isZero();
        assertThat(count("SELECT count(*) FROM pjr.load_failure WHERE fetch_request_id = ? AND file_name = ?",
                failed.fetchRequestId(), badName)).isEqualTo(1);
    }

    @Test
    void aFileOfAnotherBusinessDayFailsTheDay() {
        LocalDate day = LocalDate.parse("2025-01-22");
        putPjrDay(day, 1, List.of(sale(day, 1), sale(day.plusDays(1), 2)));

        FetchRequest failed = fetch(day, FetchRequest.Mode.REPLACE);

        assertThat(failed.status()).isEqualTo(FetchRequest.Status.FAILED);
        assertThat(failed.message()).hasValueSatisfying(m -> assertThat(m).contains("BusinessDate 2025-01-23"));
    }

    @Test
    void aCorruptZipFailsBeforeAnythingIsLoaded() throws IOException {
        LocalDate day = LocalDate.parse("2025-01-24");
        bucket().put(day, 1, PJR, List.of(sale(day, 1)), TestBucket.Corruption.WRONG_SHA256);

        FetchRequest failed = fetch(day, FetchRequest.Mode.REPLACE);

        assertThat(failed.status()).isEqualTo(FetchRequest.Status.FAILED);
        assertThat(failed.message()).hasValueSatisfying(m -> assertThat(m).contains("sha256"));
    }

    @Test
    void aDayWithoutAZipIsNotFound() {
        FetchRequest missing = fetch(LocalDate.parse("2025-01-25"), FetchRequest.Mode.REFUSE);

        assertThat(missing.status()).isEqualTo(FetchRequest.Status.NOT_FOUND);
        assertThat(missing.message()).hasValueSatisfying(m -> assertThat(m).contains("2025/january/2025-01-25/PJR/"));
    }

    @Test
    void theHighestVersionIsLoaded() {
        LocalDate day = LocalDate.parse("2025-01-26");
        putPjrDay(day, 1, List.of(sale(day, 1)));
        putPjrDay(day, 2, List.of(sale(day, 1), sale(day, 2), sale(day, 3)));

        FetchRequest loaded = fetch(day, FetchRequest.Mode.REPLACE);

        assertThat(loaded.status()).isEqualTo(FetchRequest.Status.LOADED);
        assertThat(fetchQueries.detail(loaded.fetchRequestId()).zip()).hasValueSatisfying(z -> assertThat(z.versionNo()).isEqualTo(2));
        assertThat(count("SELECT count(*) FROM pjr.event WHERE store_id = ? AND business_date = ?", TestStores.WOLF, day))
                .isEqualTo(3);
    }

    private static TestBucket.Entry sale(LocalDate day, int n) {
        return TestBucket.Entry.of(PjrXml.fileName(day, n), journal(day, transaction("SaleEvent", day, 2, n, false, false,
                "1.00", "Collected", item("normal", 1, "0123456789" + n, "GUM", "1.00", "1", "1.00", null),
                tender("cash", "generic", "1.00"))));
    }

    private static TestBucket.Entry lottoSale(LocalDate day, int n) {
        return TestBucket.Entry.of(PjrXml.fileName(day, n), journal(day, transaction("SaleEvent", day, 2, n, false, false,
                "5", "Collected", ring("normal", 57, "LOTTO ONLINE", "1", "5"), tender("cash", "generic", "5"))));
    }

    private Response start(long storeId, String day, String fileType) {
        return call(HttpMethod.POST, BASE + "/fetches",
                Map.of("storeId", storeId, "businessDate", day, "mode", "REPLACE", "fileType", fileType), admin());
    }

    private static void assertProblem(Response response, int status, String code) {
        assertThat(response.status()).as(response.body().toString()).isEqualTo(status);
        assertThat(response.body().path("code").asString()).isEqualTo(code);
    }

    private JsonNode awaitDetail(long id) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        while (Instant.now().isBefore(deadline)) {
            JsonNode detail = call(HttpMethod.GET, BASE + "/fetches/" + id, null, admin()).body();
            String status = detail.path("fetch").path("status").asString();
            if (!status.equals("CLAIMED") && !status.equals("RUNNING")) {
                return detail;
            }
        }
        throw new AssertionError("fetch " + id + " did not end");
    }

    private static String admin() {
        return TestTokens.platformAdmin(TestStores.ADMIN_ID);
    }

    private Response call(HttpMethod method, String path, Object body, String token) {
        return call(method, path, body, token, true);
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
