package com.dia.ismdtoolbackend.outbox;

import com.dia.ismdtoolbackend.config.security.TestSecurityConfig;
import com.dia.ismdtoolbackend.config.security.WithMockSecurityUser;
import com.dia.ismdtoolbackend.outbox.dto.OutboxStatusDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer tests for {@link OutboxController}: admin-only authorization, the status shape, and the
 * retry 409-when-not-retryable contract. Mirrors {@code ReconcilerControllerTest}.
 */
@WebMvcTest(controllers = OutboxController.class,
    excludeAutoConfiguration = {
        org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration.class,
        org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration.class,
        org.springframework.boot.autoconfigure.security.oauth2.client.OAuth2ClientAutoConfiguration.class,
        org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientWebSecurityAutoConfiguration.class,
        org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration.class
    })
@Import(TestSecurityConfig.class)
@ActiveProfiles("junit")
class OutboxControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OutboxAdminService adminService;

    @Test
    @WithMockSecurityUser(userId = "admin", roles = {"ROLE_ADMIN"})
    void status_asAdmin_returnsCounts() throws Exception {
        when(adminService.status()).thenReturn(new OutboxStatusDto(3, 1, 10, Instant.now()));

        mockMvc.perform(get("/api/admin/outbox/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.pending").value(3))
                .andExpect(jsonPath("$.data.failed").value(1))
                .andExpect(jsonPath("$.data.done").value(10));
    }

    @Test
    @WithMockSecurityUser(userId = "user", roles = {"ROLE_USER"})
    void status_asNonAdmin_isForbidden() throws Exception {
        mockMvc.perform(get("/api/admin/outbox/status"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockSecurityUser(userId = "user", roles = {"ROLE_USER"})
    void failed_asNonAdmin_isForbidden() throws Exception {
        mockMvc.perform(get("/api/admin/outbox/failed"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockSecurityUser(userId = "admin", roles = {"ROLE_ADMIN"})
    void drain_asAdmin_returnsAppliedCount() throws Exception {
        when(adminService.drainNow()).thenReturn(5);

        mockMvc.perform(post("/api/admin/outbox/drain"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(5));
    }

    @Test
    @WithMockSecurityUser(userId = "admin", roles = {"ROLE_ADMIN"})
    void retry_failedRow_resets() throws Exception {
        when(adminService.retry(anyLong())).thenReturn(true);

        mockMvc.perform(post("/api/admin/outbox/retry/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @WithMockSecurityUser(userId = "admin", roles = {"ROLE_ADMIN"})
    void retry_notRetryable_returns409() throws Exception {
        when(adminService.retry(anyLong())).thenReturn(false); // unknown id or not FAILED

        mockMvc.perform(post("/api/admin/outbox/retry/99"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @WithMockSecurityUser(userId = "user", roles = {"ROLE_USER"})
    void drain_asNonAdmin_isForbidden() throws Exception {
        mockMvc.perform(post("/api/admin/outbox/drain"))
                .andExpect(status().isForbidden());
    }
}
