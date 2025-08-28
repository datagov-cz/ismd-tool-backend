package com.dia.ismdtoolbackend.config;

import com.dia.ismdtoolbackend.config.cors.CorsConfig;
import com.dia.ismdtoolbackend.config.cors.CorsConfigUtil;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test for {@link CorsConfigUtil} simulating production environment.
 *
 * @see CorsConfig
 */
@SpringBootTest
@ActiveProfiles("production")
@AutoConfigureMockMvc
class CorsConfigProductionTest {

    @Autowired
    private Environment environment;

    @Autowired
    private MockMvc mockMvc;

    @ParameterizedTest
    @ValueSource(strings = {
            "https://www.domain.com"
    })
    void allowedOriginsTest(String testOrigin) throws Exception {
        String activeProfiles = String.join(", ", environment.getActiveProfiles());
        System.out.println("Active Profiles: " + activeProfiles);

        mockMvc.perform(get("/actuator/health")
                        .header("Origin", testOrigin))
                .andExpect(status().isOk())
                .andExpect(header().exists("Access-Control-Allow-Origin"))
                .andExpect(header().string("Access-Control-Allow-Origin", testOrigin));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://www.twitter.com",
            "http://localhost:3000",
            "https://malicious-site.com",
            "https://192.168.1.1"
    })
    void blockedOriginsTest(String testOrigin) throws Exception {
        mockMvc.perform(get("/actuator/health")
                        .header("Origin", testOrigin))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
