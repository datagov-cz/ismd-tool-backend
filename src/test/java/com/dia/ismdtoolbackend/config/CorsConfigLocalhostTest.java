package com.dia.ismdtoolbackend.config;

import com.dia.ismdtoolbackend.config.cors.CorsConfig;
import com.dia.ismdtoolbackend.config.cors.CorsConfigUtil;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for {@link CorsConfigUtil} simulating localhost environment
 *
 * @see CorsConfig
 */
@SpringBootTest
@ActiveProfiles("local")
@AutoConfigureMockMvc
class CorsConfigLocalhostTest {

    @Autowired
    private MockMvc mockMvc;

    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost:3000",             // Local development HTTP
            "https://localhost:3000",            // Local development HTTPS
            "http://192.168.0.18",               // Local network
            "https://192.168.0.18",              // Local network HTTPS
            "http://192.168.1.20",               // Local network different subnet
            "https://b2b6-89-103-185-10.ngrok-free.app",  // Ngrok tunnel
            "https://t1.domain.org",             // Cloudflare tunnel
    })
    void allowedOriginsTest(String testOrigin) throws Exception {
        mockMvc.perform(get("/actuator/health")
                        .header("Origin", testOrigin))
                .andExpect(status().isOk())
                .andExpect(header().exists("Access-Control-Allow-Origin"))
                .andExpect(header().string("Access-Control-Allow-Origin", testOrigin));
    }
}

