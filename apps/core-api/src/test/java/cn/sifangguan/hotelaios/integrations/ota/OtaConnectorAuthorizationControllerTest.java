package cn.sifangguan.hotelaios.integrations.ota;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OtaConnectorAuthorizationControllerTest {
    @Test
    void exposesFrozenStatusContractWithNoStoreHeaders() throws Exception {
        OtaConnectorAuthorizationService service = mock(OtaConnectorAuthorizationService.class);
        when(service.status(OtaPilotScope.HOTEL_ID, "CTRIP")).thenReturn(
                new OtaAuthorizationModels.StatusResponse(
                        OtaPilotScope.HOTEL_ID, "002", "CTRIP",
                        OtaAuthorizationModels.BindingStatus.DISCOVERY_REQUIRED,
                        OtaAuthorizationModels.SessionStatus.REAUTH_REQUIRED, null));
        MockMvc mvc = mvc(service);

        mvc.perform(get("/api/v1/ota/connector-authorizations")
                        .param("hotelId", OtaPilotScope.HOTEL_ID.toString())
                        .param("platformCode", "CTRIP"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(jsonPath("$.hotelCode").value("002"))
                .andExpect(jsonPath("$.bindingStatus").value("DISCOVERY_REQUIRED"))
                .andExpect(jsonPath("$.sessionStatus").value("REAUTH_REQUIRED"));
    }

    @Test
    void startsThroughTheFrozenActionEndpointAndReturnsOnlyProjectedFields() throws Exception {
        OtaConnectorAuthorizationService service = mock(OtaConnectorAuthorizationService.class);
        when(service.start(any(), anyString())).thenReturn(new OtaAuthorizationModels.StartResponse(
                java.util.UUID.fromString("11111111-1111-4111-8111-111111111111"),
                "WAITING_FOR_USER", true,
                OtaPilotScope.AUTHORIZATION_URL_PREFIX + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                null, OtaAuthorizationModels.BindingStatus.DISCOVERY_REQUIRED,
                OtaAuthorizationModels.SessionStatus.REAUTH_REQUIRED));
        MockMvc mvc = mvc(service);

        mvc.perform(post("/api/v1/ota/connector-authorizations/actions/start")
                        .header("Idempotency-Key", "discovery-002-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"hotelId":"20000000-0000-4000-8000-000000000002",
                                "platformCode":"CTRIP","action":"DISCOVERY",
                                "reason":"首次身份核验","idempotencyKey":"discovery-002-0001"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.challengeId").value("11111111-1111-4111-8111-111111111111"))
                .andExpect(jsonPath("$.authorizationRequired").value(true))
                .andExpect(jsonPath("$.bindingStatus").value("DISCOVERY_REQUIRED"))
                .andExpect(jsonPath("$.sessionStatus").value("REAUTH_REQUIRED"));
    }

    @Test
    void rejectsHeaderAndBodyIdempotencyKeyMismatchBeforeServiceCall() throws Exception {
        OtaConnectorAuthorizationService service = mock(OtaConnectorAuthorizationService.class);
        MockMvc mvc = mvc(service);

        mvc.perform(post("/api/v1/ota/connector-authorizations/actions/start")
                        .header("Idempotency-Key", "discovery-002-header")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"hotelId":"20000000-0000-4000-8000-000000000002",
                                "platformCode":"CTRIP","action":"DISCOVERY",
                                "reason":"首次身份核验","idempotencyKey":"discovery-002-body"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("OTA_AUTHORIZATION_IDEMPOTENCY_HEADER_MISMATCH"));
        verify(service, never()).start(any(), anyString());
    }

    private static MockMvc mvc(OtaConnectorAuthorizationService service) {
        return MockMvcBuilders.standaloneSetup(new OtaConnectorAuthorizationController(service))
                .setControllerAdvice(new OtaAuthorizationExceptionHandler())
                .build();
    }
}
