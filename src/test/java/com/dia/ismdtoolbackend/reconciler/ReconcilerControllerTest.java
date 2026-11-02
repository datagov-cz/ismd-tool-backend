package com.dia.ismdtoolbackend.reconciler;

import com.dia.ismdtoolbackend.config.security.WithMockSecurityUser;
import com.dia.ismdtoolbackend.testsupport.web.ControllerSliceTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Web-layer tests for {@link ReconcilerController}: admin-only authorization and the
 * 409-on-run-in-progress contract. Method security ({@code @PreAuthorize}) is exercised via
 * {@link TestSecurityConfig} + {@link WithMockSecurityUser}.
 */
@ControllerSliceTest(controllers = ReconcilerController.class)
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
        when(runService.run("ADMIN:admin")).thenReturn(emptyReport("ADMIN:admin"));

        mockMvc.perform(post("/api/admin/reconciler/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.triggeredBy").value("ADMIN:admin"));

        // The run is attributed to the authenticated admin.
        verify(runService).run("ADMIN:admin");
    }

    @Test
    @WithMockSecurityUser(userId = "user", roles = {"ROLE_USER"})
    void run_asNonAdmin_isForbidden() throws Exception {
        mockMvc.perform(post("/api/admin/reconciler/run"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(runService);
    }

    @Test
    @WithMockSecurityUser(userId = "admin", roles = {"ROLE_ADMIN"})
    void run_whenAlreadyInProgress_returns409() throws Exception {
        when(runService.run("ADMIN:admin")).thenReturn(null); // a run is already in progress

        mockMvc.perform(post("/api/admin/reconciler/run"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false));

        verify(runService).run("ADMIN:admin");
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
