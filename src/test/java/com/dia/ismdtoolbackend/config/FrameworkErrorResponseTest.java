package com.dia.ismdtoolbackend.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Requests Spring MVC itself rejects get their own status and code instead of the catch-all 500. */
class FrameworkErrorResponseTest {

    @RestController
    static class Endpoints {
        @GetMapping("/things")
        Map<String, String> list() {
            return Map.of("ok", "yes");
        }

        @PostMapping(value = "/things", consumes = MediaType.APPLICATION_JSON_VALUE)
        Map<String, String> create(@RequestBody Map<String, String> body) {
            return body;
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Endpoints())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void wrongMethodIs405WithTheAllowedOnes() throws Exception {
        mvc.perform(delete("/things"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("GET"), org.hamcrest.Matchers.containsString("POST"))))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").value("Tato metoda není pro danou adresu podporována."));
    }

    @Test
    void unsupportedBodyFormatIs415() throws Exception {
        mvc.perform(post("/things").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void unknownPathIs404() throws Exception {
        mvc.perform(get("/nothing-here"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ENDPOINT_NOT_FOUND"));
    }

    @Test
    void knownEndpointStillAnswers() throws Exception {
        mvc.perform(get("/things"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value("yes"));
    }
}
