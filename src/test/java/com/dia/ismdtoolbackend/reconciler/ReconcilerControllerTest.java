package com.dia.ismdtoolbackend.reconciler;

import com.dia.ismdtoolbackend.config.security.TestSecurityConfig;
import com.dia.ismdtoolbackend.config.security.WithMockSecurityUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Web-layer tests for {@link ReconcilerController}: admin-only authorization and the
 * 409-on-run-in-progress contract. Method security ({@code @PreAuthorize}) is exercised via
 * {@link TestSecurityConfig} + {@link WithMockSecurityUser}.
 */
@WebMvcTest(controllers = ReconcilerController.class,
    excludeAutoConfiguration = {
        org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration.class,
        org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration.class,
        org.springframework.boot.autoconfigure.security.oauth2.client.OAuth2ClientAutoConfiguration.class,
        org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientWebSecurityAutoConfiguration.class,
        org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration.class
    })
@Import(TestSecurityConfig.class)
@ActiveProfiles("junit")
class ReconcilerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReconcilerRunService runService;

    private ReconciliationReport emptyReport(String triggeredBy) {
        return new ReconciliationReport(Instant.now(), Instant.now(), triggeredBy, 0, 0, 0, List.of());
    }

    @Test
    @WithMockSecurityUser(userId = "admin", roles = {"ROLE_ADMIN"})
    void run_asAdmin_returnsReport() throws Exception {
        when(runService.run(anyString())).thenReturn(emptyReport("ADMIN:admin"));

        mockMvc.perform(post("/api/admin/reconciler/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.triggeredBy").value("ADMIN:admin"));
    }

    @Test
    @WithMockSecurityUser(userId = "user", roles = {"ROLE_USER"})
    void run_asNonAdmin_isForbidden() throws Exception {
        mockMvc.perform(post("/api/admin/reconciler/run"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockSecurityUser(userId = "admin", roles = {"ROLE_ADMIN"})
    void run_whenAlreadyInProgress_returns409() throws Exception {
        when(runService.run(anyString())).thenReturn(null); // a run is already in progress

        mockMvc.perform(post("/api/admin/reconciler/run"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @WithMockSecurityUser(userId = "admin", roles = {"ROLE_ADMIN"})
    void report_noRunYet_returnsNullDataNotError() throws Exception {
        when(runService.getLastReport()).thenReturn(null);

        mockMvc.perform(get("/api/admin/reconciler/report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @WithMockSecurityUser(userId = "user", roles = {"ROLE_USER"})
    void report_asNonAdmin_isForbidden() throws Exception {
        mockMvc.perform(get("/api/admin/reconciler/report"))
                .andExpect(status().isForbidden());
    }
}
