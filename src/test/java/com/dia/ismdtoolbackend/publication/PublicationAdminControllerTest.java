package com.dia.ismdtoolbackend.publication;

import com.dia.ismdtoolbackend.config.security.WithMockSecurityUser;
import com.dia.ismdtoolbackend.exception.PublicationFailedException;
import com.dia.ismdtoolbackend.exception.PublicationFilePrunedException;
import com.dia.ismdtoolbackend.exception.PublicationInProgressException;
import com.dia.ismdtoolbackend.publication.archive.PublicationArchive;
import com.dia.ismdtoolbackend.publication.archive.PublicationSummary;
import com.dia.ismdtoolbackend.testsupport.web.ControllerSliceTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Admin-only authorization and the mapping of the service's outcomes through {@code GlobalExceptionHandler}. */
@ControllerSliceTest(controllers = PublicationAdminController.class)
class PublicationAdminControllerTest {

    private static final String RUN = "/api/admin/publikace/run";
    private static final String BY = "ADMIN:admin";

    @Autowired private MockMvc mockMvc;
    @MockitoBean private PublicationService service;
    @MockitoBean private PublicationArchive archive;

    @Test
    @WithMockSecurityUser(userId = "admin", roles = {"ROLE_ADMIN"})
    void run_asAdmin_returnsTheCommittedRun() throws Exception {
        when(service.publish(BY)).thenReturn(
                new PublicationRun(5L, Instant.parse("2026-11-18T10:00:00Z"), 3, 2, true, true));

        mockMvc.perform(post(RUN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(5))
                .andExpect(jsonPath("$.data.vocabularies").value(3))
                .andExpect(jsonPath("$.data.diagrams").value(2))
                .andExpect(jsonPath("$.data.complete").value(true));
    }

    @Test
    @WithMockSecurityUser(userId = "user", roles = {"ROLE_USER"})
    void run_asNonAdmin_isForbidden() throws Exception {
        mockMvc.perform(post(RUN)).andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    @WithMockSecurityUser(userId = "admin", roles = {"ROLE_ADMIN"})
    void run_whileAnotherRunsInProgress_returns409() throws Exception {
        when(service.publish(BY)).thenThrow(new PublicationInProgressException("Publikace již probíhá."));

        mockMvc.perform(post(RUN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PUBLICATION_IN_PROGRESS"));
    }

    @Test
    @WithMockSecurityUser(userId = "admin", roles = {"ROLE_ADMIN"})
    void run_failed_returns500WithTheReason() throws Exception {
        when(service.publish(BY)).thenThrow(new PublicationFailedException("Publikace selhala.", null));

        mockMvc.perform(post(RUN))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("PUBLICATION_FAILED"))
                .andExpect(jsonPath("$.message").value("Publikace selhala."));
    }

    @Test
    @WithMockSecurityUser(userId = "admin", roles = {"ROLE_ADMIN"})
    void file_asAdmin_downloadsTheStoredFile() throws Exception {
        when(archive.file(5L, "slovniky.jsonld")).thenReturn(new PublicationArchive.ArchivedFile(
                "slovniky.jsonld", 2, "\"abc\"", "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        mockMvc.perform(get("/api/admin/publikace/5/slovniky.jsonld"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/ld+json;charset=UTF-8"))
                .andExpect(header().string("ETag", "\"abc\""))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"publikace-5-slovniky.jsonld\""))
                .andExpect(content().string("{}"));
    }

    @Test
    @WithMockSecurityUser(userId = "admin", roles = {"ROLE_ADMIN"})
    void file_pastTheRetention_returns410() throws Exception {
        when(archive.file(1L, "slovniky.jsonld")).thenThrow(new PublicationFilePrunedException("už není uložen"));

        mockMvc.perform(get("/api/admin/publikace/1/slovniky.jsonld"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.errorCode").value("PUBLICATION_FILE_PRUNED"));
    }

    @Test
    @WithMockSecurityUser(userId = "admin", roles = {"ROLE_ADMIN"})
    void file_unknown_returns404() throws Exception {
        when(archive.file(9L, "slovniky.jsonld")).thenThrow(new jakarta.persistence.EntityNotFoundException("nic"));

        mockMvc.perform(get("/api/admin/publikace/9/slovniky.jsonld")).andExpect(status().isNotFound());
    }

    @Test
    @WithMockSecurityUser(userId = "user", roles = {"ROLE_USER"})
    void file_asNonAdmin_isForbidden() throws Exception {
        mockMvc.perform(get("/api/admin/publikace/5/slovniky.jsonld")).andExpect(status().isForbidden());

        verifyNoInteractions(archive);
    }

    @Test
    @WithMockSecurityUser(userId = "admin", roles = {"ROLE_ADMIN"})
    void history_asAdmin_returnsTheStoredPublications() throws Exception {
        when(archive.history(0, 50)).thenReturn(List.of(new PublicationSummary(5L,
                Instant.parse("2026-11-18T10:00:00Z"), "ADMIN:admin", true, true, 3, 2, "https://x/kontext.jsonld",
                List.of(new PublicationSummary.FileSummary("slovniky.jsonld", 10, "\"abc\"", false)))));

        mockMvc.perform(get("/api/admin/publikace/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(5))
                .andExpect(jsonPath("$.data[0].triggeredBy").value("ADMIN:admin"))
                .andExpect(jsonPath("$.data[0].files[0].stored").value(false));
    }

    @Test
    @WithMockSecurityUser(userId = "admin", roles = {"ROLE_ADMIN"})
    void history_passesPaging_andRejectsAnOutOfRangeSize() throws Exception {
        when(archive.history(2, 10)).thenReturn(List.of());

        mockMvc.perform(get("/api/admin/publikace/history").param("page", "2").param("size", "10"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/publikace/history").param("size", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/admin/publikace/history").param("size", "201"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockSecurityUser(userId = "user", roles = {"ROLE_USER"})
    void history_asNonAdmin_isForbidden() throws Exception {
        mockMvc.perform(get("/api/admin/publikace/history")).andExpect(status().isForbidden());

        verifyNoInteractions(archive);
    }
}
