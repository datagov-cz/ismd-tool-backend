package com.dia.ismdtoolbackend.audit;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The admin read of the delete audit trail: role, filters passed through, page shape, parameter bounds. */
@ControllerSliceTest(controllers = DeleteAuditController.class)
class DeleteAuditControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DeleteAuditRepository repository;

    @Test
    @WithMockSecurityUser(userId = "admin", roles = {"ROLE_ADMIN"})
    void asAdmin_returnsAPageWithTheFiltersApplied() throws Exception {
        Instant from = Instant.parse("2026-11-01T00:00:00Z");
        DeleteAuditEntry row = new DeleteAuditEntry();
        row.setId(9L);
        row.setDeletedAt(Instant.parse("2026-11-02T10:00:00Z"));
        row.setActorId("kc-sub-1");
        row.setEntityType(DeletedEntityType.DIAGRAM);
        row.setEntityId(4L);
        row.setEntityName("Přehled");
        when(repository.findPage("DIAGRAM", "kc-sub-1", from, null, 10, 20)).thenReturn(List.of(row));
        when(repository.countMatching("DIAGRAM", "kc-sub-1", from, null)).thenReturn(21L);

        mockMvc.perform(get("/api/admin/audit/deletes")
                        .param("type", "DIAGRAM").param("actorId", "kc-sub-1")
                        .param("from", "2026-11-01T00:00:00Z").param("limit", "10").param("offset", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(21))
                .andExpect(jsonPath("$.data.limit").value(10))
                .andExpect(jsonPath("$.data.offset").value(20))
                .andExpect(jsonPath("$.data.items[0].entityType").value("DIAGRAM"))
                .andExpect(jsonPath("$.data.items[0].entityName").value("Přehled"))
                .andExpect(jsonPath("$.data.items[0].actorId").value("kc-sub-1"));
    }

    @Test
    @WithMockSecurityUser(userId = "admin", roles = {"ROLE_ADMIN"})
    void withoutParameters_usesTheDefaultPage() throws Exception {
        mockMvc.perform(get("/api/admin/audit/deletes")).andExpect(status().isOk());

        verify(repository).findPage(null, null, null, null, DeleteAuditController.DEFAULT_LIMIT, 0);
    }

    @Test
    @WithMockSecurityUser(userId = "admin", roles = {"ROLE_ADMIN"})
    void aLimitOverTheMaximumIsRejected() throws Exception {
        mockMvc.perform(get("/api/admin/audit/deletes").param("limit", String.valueOf(DeleteAuditController.MAX_LIMIT + 1)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/admin/audit/deletes").param("offset", "-1"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/admin/audit/deletes").param("type", "NOPE"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(repository);
    }

    @Test
    @WithMockSecurityUser(userId = "user", roles = {"ROLE_USER"})
    void asNonAdmin_isForbidden() throws Exception {
        mockMvc.perform(get("/api/admin/audit/deletes")).andExpect(status().isForbidden());

        verifyNoInteractions(repository);
    }
}
