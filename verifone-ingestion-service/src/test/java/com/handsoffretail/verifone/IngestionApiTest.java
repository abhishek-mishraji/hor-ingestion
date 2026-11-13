package com.handsoffretail.verifone;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The HTTP API with hor-auth cookie tokens and the CSRF header, over real HTTP. */
class IngestionApiTest extends IngestionIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String BASE = "/api/ingestion/verifone";

    @LocalServerPort
    private int port;

    @Test
    void everyEndpointNeedsAPlatformAdmin() {
        assertThat(call(HttpMethod.GET, BASE + "/fetches", null, null).status()).isEqualTo(401);
        Response client = call(HttpMethod.GET, BASE + "/fetches", null, TestTokens.clientUser(5, List.of(TestStores.TIKI)));
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
    void storesListsOnlySyncedVerifoneStores() {
        JsonNode stores = call(HttpMethod.GET, BASE + "/stores", null, admin()).body();
        List<String> codes = stores.valueStream().map(s -> s.path("storeCode").asString()).toList();
        assertThat(codes).contains("8730_IN-47872", "CLOSED_1").doesNotContain("800_NY-13208");
    }

    @Test
    void fetchThroughTheApiThenReadDetailDaysAndDepartments() {
        Response started = call(HttpMethod.POST, BASE + "/fetches",
                Map.of("storeId", TestStores.TIKI, "businessDate", "2026-09-08", "mode", "REPLACE"), admin());
        assertThat(started.status()).isEqualTo(202);
        long id = started.body().path("fetchRequestId").asLong();

        JsonNode detail = awaitDetail(id);
        assertThat(detail.path("fetch").path("status").asString()).isEqualTo("LOADED");
        assertThat(detail.path("zip").path("version").asInt()).isEqualTo(1);
        JsonNode file = detail.path("files").get(0);
        assertThat(file.path("rows").path("tickets").asLong()).isEqualTo(611);
        assertThat(file.path("checks").valueStream().filter(c -> c.path("hard").asBoolean())
                .allMatch(c -> c.path("passed").asBoolean())).isTrue();

        JsonNode available = call(HttpMethod.GET, BASE + "/stores/1/available-days?year=2026&month=9", null, admin()).body();
        assertThat(available.valueStream().map(JsonNode::asString).toList()).contains("2026-09-01", "2026-09-17");
        JsonNode loaded = call(HttpMethod.GET, BASE + "/stores/1/loaded-days?from=2026-09-01&to=2026-09-30", null, admin()).body();
        assertThat(loaded.valueStream().map(d -> d.path("businessDate").asString()).toList()).contains("2026-09-08");

        // The lottery class is fixed by the department name; nobody sets it.
        JsonNode departments = call(HttpMethod.GET, BASE + "/stores/1/departments", null, admin()).body();
        JsonNode lotto = departments.valueStream().filter(d -> d.path("deptNumber").asInt() == 29).findFirst().orElseThrow();
        assertThat(lotto.path("currentName").asString()).isEqualTo("Lotto");
        assertThat(lotto.path("names").get(0).path("lastSeen").asString()).isNotBlank();
        assertThat(lotto.path("lotteryClass").asString()).isEqualTo("LOTTERY_SALE");
        assertThat(departments.valueStream().filter(d -> d.path("deptNumber").asInt() == 28)
                .map(d -> d.path("lotteryClass").asString())).allMatch("LOTTERY_PAYOUT"::equals);
        assertThat(departments.valueStream().filter(d -> d.path("deptNumber").asInt() != 28 && d.path("deptNumber").asInt() != 29)
                .filter(d -> d.has("lotteryClass"))).isEmpty();

        Response history = call(HttpMethod.GET, BASE + "/fetches?storeId=1&limit=5", null, admin());
        assertThat(history.body().path("items").valueStream().map(f -> f.path("id").asLong()).toList()).contains(id);
    }

    @Test
    void theLotteryClassFollowsTheDepartmentName() {
        assertThat(lotteryClass("Lotto", "norm")).isEqualTo("LOTTERY_SALE");
        assertThat(lotteryClass("LOTTERY", "norm")).isEqualTo("LOTTERY_SALE");
        assertThat(lotteryClass("Lotto Payout", "neg")).isEqualTo("LOTTERY_PAYOUT");
        assertThat(lotteryClass("Lottery Payout", "norm")).isEqualTo("LOTTERY_PAYOUT");
        assertThat(lotteryClass("Lotto", "neg")).isEqualTo("LOTTERY_PAYOUT");
        assertThat(lotteryClass("DRINKS", "norm")).isNull();
        assertThat(lotteryClass("Bottle Return", "neg")).isNull();
    }

    private String lotteryClass(String name, String type) {
        return jdbc.queryForObject("SELECT master.lottery_class_for(?, ?)", String.class, name, type);
    }

    @Test
    void malformedRequestsAre400AndRuleBreaksAre422() {
        Response bad = call(HttpMethod.POST, BASE + "/fetches", Map.of("storeId", TestStores.TIKI, "mode", "REPLACE"), admin());
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.body().path("code").asString()).isEqualTo("VALIDATION_FAILED");
        Response future = call(HttpMethod.POST, BASE + "/fetches",
                Map.of("storeId", TestStores.TIKI, "businessDate", "2099-01-01", "mode", "REPLACE"), admin());
        assertThat(future.status()).isEqualTo(422);
        assertThat(future.body().path("code").asString()).isEqualTo("DATE_IN_FUTURE");
    }

    private JsonNode awaitDetail(long id) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        while (Instant.now().isBefore(deadline)) {
            JsonNode detail = call(HttpMethod.GET, BASE + "/fetches/" + id, null, admin()).body();
            if (!detail.path("fetch").path("status").asString().equals("RUNNING")) {
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
