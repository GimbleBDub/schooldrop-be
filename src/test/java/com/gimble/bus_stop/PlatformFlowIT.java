package com.gimble.bus_stop;

import com.gimble.bus_stop.payments.WebhookSignature;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end checks against the real migrated schema and the seeded demo data (Postgres 17 in a container).
 * Paths are relative to the /api context path. scripts/e2e.py covers far more against a running server.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles({"local", "test"})
class PlatformFlowIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Test
    void parentBooksPaysAndDriverSeesThePassenger() throws Exception {
        String parent = login("parent@schooldrop.local");
        JsonNode children = call(get("/v1/parent/children").header("Authorization", parent), 200);
        String lesedi = find(children, "firstName", "Lesedi").get("id").asText();

        JsonNode offers = call(get("/v1/parent/children/" + lesedi + "/offers").header("Authorization", parent), 200);
        assertThat(offers.size()).isEqualTo(1);
        String offerId = offers.get(0).get("id").asText();

        JsonNode booking = call(post("/v1/parent/bookings").header("Authorization", parent)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("offerId", offerId, "childId", lesedi, "plan", "MONTHLY",
                        "month", YearMonth.now().plusMonths(1).toString(), "pickupAddress", "Plot 1, Phakalane"))), 201);
        assertThat(booking.get("status").asText()).isEqualTo("PENDING_PAYMENT");

        JsonNode payment = call(post("/v1/parent/bookings/" + booking.get("id").asText() + "/pay")
                .header("Authorization", parent).contentType(MediaType.APPLICATION_JSON)
                .content("{\"method\":\"ORANGE_MONEY\",\"payerPhone\":\"+26771234567\"}"), 200);

        // The gateway reports success through the signed webhook
        byte[] body = ("{\"reference\":\"" + payment.get("providerReference").asText() + "\",\"status\":\"SUCCEEDED\"}")
                .getBytes(StandardCharsets.UTF_8);
        mvc.perform(post("/v1/payments/webhook").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Signature", "forged")).andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/payments/webhook").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Signature", WebhookSignature.sign("local-only-webhook-secret", body)))
                .andExpect(status().isNoContent());

        JsonNode paid = call(get("/v1/parent/bookings/" + booking.get("id").asText()).header("Authorization", parent), 200);
        assertThat(paid.get("status").asText()).isEqualTo("ACTIVE");

        String driver = login("driver@schooldrop.local");
        JsonNode passengers = call(get("/v1/driver/bookings?status=ACTIVE").header("Authorization", driver), 200);
        assertThat(passengers.findValuesAsString("childName")).contains("Lesedi Kgosi", "Neo Kgosi");
    }

    @Test
    void newDriverGoesThroughVerification() throws Exception {
        JsonNode registered = call(post("/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountType\":\"DRIVER\",\"email\":\"tumi@x.bw\",\"phone\":\"+26774000004\","
                        + "\"password\":\"password123\"}"), 201);
        assertThat(registered.get("user").get("onboardingComplete").asBoolean()).isFalse();
        String driver = "Bearer " + registered.get("accessToken").asText();

        // Driver features are locked until onboarding is done
        mvc.perform(get("/v1/driver/profile").header("Authorization", driver)).andExpect(status().isForbidden());
        JsonNode me = call(put("/v1/onboarding/driver").header("Authorization", driver)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"firstName\":\"Tumi\",\"lastName\":\"New\",\"omangNumber\":\"987654321\"}"), 200);
        assertThat(me.get("onboardingComplete").asBoolean()).isTrue();

        // Can't submit without documents; uploads are sniffed, not trusted
        mvc.perform(post("/v1/driver/submit").header("Authorization", driver)).andExpect(status().isBadRequest());
        mvc.perform(multipart("/v1/driver/documents").file(new MockMultipartFile("file", "x.pdf", "application/pdf",
                        "<html>".getBytes())).param("type", "OMANG_FRONT").header("Authorization", driver))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/v1/driver/documents").file(new MockMultipartFile("file", "omang.pdf", "application/pdf",
                        "%PDF-1.4 test".getBytes())).param("type", "OMANG_FRONT").header("Authorization", driver))
                .andExpect(status().isCreated());

        JsonNode profile = call(get("/v1/driver/profile").header("Authorization", driver), 200);
        assertThat(profile.get("omangNumber").asText()).isEqualTo("******321");
        assertThat(profile.get("status").asText()).isEqualTo("PENDING_DOCUMENTS");

        // Unapproved drivers can't publish offers; parents can't use driver endpoints
        mvc.perform(post("/v1/driver/offers").header("Authorization", driver).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isBadRequest());
        mvc.perform(get("/v1/driver/profile").header("Authorization", login("parent@schooldrop.local")))
                .andExpect(status().isForbidden());

        // The verifier sees the full Omang number
        String admin = login("admin@schooldrop.local", "ChangeMe-Local-123");
        JsonNode full = call(get("/v1/admin/drivers/" + profile.get("id").asText()).header("Authorization", admin), 200);
        assertThat(full.get("omangNumber").asText()).isEqualTo("987654321");
    }

    @Test
    void healthIsPublicAndApiNeedsAToken() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/v1/parent/children")).andExpect(status().isUnauthorized());
    }

    private String login(String email) throws Exception {
        return login(email, "Demo-Pass-123");
    }

    private String login(String email, String password) throws Exception {
        JsonNode auth = call(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", password))), 200);
        return "Bearer " + auth.get("accessToken").asText();
    }

    private JsonNode call(org.springframework.test.web.servlet.RequestBuilder request, int expectedStatus) throws Exception {
        var result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString()).isEqualTo(expectedStatus);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private static JsonNode find(JsonNode array, String field, String value) {
        for (JsonNode node : array) {
            if (value.equals(node.get(field).asText())) return node;
        }
        throw new AssertionError("No element with " + field + "=" + value);
    }
}
