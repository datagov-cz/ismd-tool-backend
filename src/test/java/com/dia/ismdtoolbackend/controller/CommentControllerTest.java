package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.config.security.TestOntologySecurityService;
import com.dia.ismdtoolbackend.config.security.TestSecurityConfig;
import com.dia.ismdtoolbackend.config.security.WithMockSecurityUser;
import com.dia.ismdtoolbackend.exception.CommentException;
import com.dia.ismdtoolbackend.exception.CommentNotFoundException;
import com.dia.ismdtoolbackend.exception.OntologyValidationException;
import com.dia.ismdtoolbackend.models.CommentModel;
import com.dia.ismdtoolbackend.service.CommentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for CommentController with security context.
 * <p>
 * Uses @WebMvcTest to load only the web layer with Spring Security enabled.
 * Authentication is provided via @WithMockSecurityUser annotation.
 * Authorization checks (@PreAuthorize) are mocked via OntologySecurityService.
 * <p>
 * Note: @MockBean is deprecated in Spring Boot 3.4+ but remains the recommended
 * approach for @WebMvcTest until a clear migration path is provided.
 */
@WebMvcTest(controllers = CommentController.class,
    excludeAutoConfiguration = {
        org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration.class,
        org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration.class,
        org.springframework.boot.autoconfigure.security.oauth2.client.OAuth2ClientAutoConfiguration.class,
        org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientWebSecurityAutoConfiguration.class,
        org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration.class
    })
@Import({TestSecurityConfig.class, TestOntologySecurityService.class, com.dia.ismdtoolbackend.config.GlobalExceptionHandler.class})
@ActiveProfiles("test")
class CommentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CommentService commentService;

    @BeforeEach
    void setUp() {
        // Reset security service to allow modifications by default
        TestOntologySecurityService.reset();
    }

    // ========== Post Comment Tests ==========

    @Test
    @WithMockSecurityUser(userId = "user123")
    void testPostComment_Success() throws Exception {
        String userId = "user123";
        String jsonRequest = """
                {
                    "ontologyIRI": "http://example.org/ontology",
                    "conceptIRI": "http://example.org/concept",
                    "comment": "This is a test comment"
                }
                """;

        CommentModel expectedComment = new CommentModel();
        expectedComment.setId(1L);
        expectedComment.setUserId(userId);
        expectedComment.setOntologyIRI("http://example.org/ontology");
        expectedComment.setConceptIRI("http://example.org/concept");
        expectedComment.setComment("This is a test comment");

        when(commentService.postComment(any(), eq(userId)))
                .thenReturn(expectedComment);

        mockMvc.perform(post("/api/comment/post")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.userId").value(userId))
                .andExpect(jsonPath("$.data.ontologyIRI").value("http://example.org/ontology"))
                .andExpect(jsonPath("$.data.conceptIRI").value("http://example.org/concept"))
                .andExpect(jsonPath("$.data.comment").value("This is a test comment"))
                .andExpect(jsonPath("$.message").value("Komentář úspěšně přidán."));
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void testPostComment_ServiceException() throws Exception {
        String userId = "user123";
        String jsonRequest = """
                {
                    "ontologyIRI": "http://example.org/ontology",
                    "conceptIRI": "http://example.org/concept",
                    "comment": "This is a test comment"
                }
                """;

        when(commentService.postComment(any(), eq(userId)))
                .thenThrow(new RuntimeException("Nastala neočekávaná chyba."));

        mockMvc.perform(post("/api/comment/post")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Nastala neočekávaná chyba."));
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void testPostComment_EmptyComment() throws Exception {
        String userId = "user123";
        String jsonRequest = """
                {
                    "ontologyIRI": "http://example.org/ontology",
                    "conceptIRI": "http://example.org/concept",
                    "comment": ""
                }
                """;

        when(commentService.postComment(any(), eq(userId)))
                .thenThrow(new CommentException("Komentář nesmí být prázdný"));

        mockMvc.perform(post("/api/comment/post")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Komentář nesmí být prázdný"));
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void testPostComment_InvalidOntologyIRI() throws Exception {
        String userId = "user123";
        String jsonRequest = """
                {
                    "ontologyIRI": "invalid-iri",
                    "conceptIRI": "http://example.org/concept",
                    "comment": "This is a test comment"
                }
                """;

        when(commentService.postComment(any(), eq(userId)))
                .thenThrow(new OntologyValidationException("Neplatné IRI slovníku"));

        mockMvc.perform(post("/api/comment/post")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Neplatné IRI slovníku"));
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void testPostComment_LongComment() throws Exception {
        String userId = "user123";
        String longComment = "A".repeat(1000);
        String jsonRequest = String.format("""
                {
                    "ontologyIRI": "http://example.org/ontology",
                    "conceptIRI": "http://example.org/concept",
                    "comment": "%s"
                }
                """, longComment);

        CommentModel expectedComment = new CommentModel();
        expectedComment.setId(1L);
        expectedComment.setUserId(userId);
        expectedComment.setOntologyIRI("http://example.org/ontology");
        expectedComment.setConceptIRI("http://example.org/concept");
        expectedComment.setComment(longComment);

        when(commentService.postComment(any(), eq(userId)))
                .thenReturn(expectedComment);

        mockMvc.perform(post("/api/comment/post")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.comment").value(longComment))
                .andExpect(jsonPath("$.message").value("Komentář úspěšně přidán."));
    }

    // ========== Delete Comment Tests ==========

    @Test
    @WithMockSecurityUser(userId = "user123")
    void testDeleteComment_Success() throws Exception {
        Long commentId = 1L;

        TestOntologySecurityService.setAllowModify(true);
        doNothing().when(commentService).deleteComment(commentId);

        mockMvc.perform(delete("/api/comment/{commentId}/delete", commentId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Komentář úspěšně smazán."));
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void testDeleteComment_NotFound() throws Exception {
        Long commentId = 999L;

        TestOntologySecurityService.setAllowModify(true);
        doThrow(new CommentNotFoundException("Komentář s ID 999 nebyl nalezen"))
                .when(commentService).deleteComment(commentId);

        mockMvc.perform(delete("/api/comment/{commentId}/delete", commentId))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Komentář s ID 999 nebyl nalezen"));
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void testDeleteComment_CommentException() throws Exception {
        Long commentId = 1L;

        TestOntologySecurityService.setAllowModify(true);
        doThrow(new CommentException("Chyba při mazání komentáře"))
                .when(commentService).deleteComment(commentId);

        mockMvc.perform(delete("/api/comment/{commentId}/delete", commentId))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Chyba při mazání komentáře"));
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void testDeleteComment_UnexpectedException() throws Exception {
        Long commentId = 1L;

        TestOntologySecurityService.setAllowModify(true);
        doThrow(new RuntimeException("Nastala neočekávaná chyba."))
                .when(commentService).deleteComment(commentId);

        mockMvc.perform(delete("/api/comment/{commentId}/delete", commentId))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Nastala neočekávaná chyba."));
    }

    @Test
    @WithMockSecurityUser(userId = "user123")
    void testDeleteComment_InvalidCommentId() throws Exception {
        Long commentId = -1L;

        TestOntologySecurityService.setAllowModify(true);
        doThrow(new CommentException("Neplatné ID komentáře"))
                .when(commentService).deleteComment(commentId);

        mockMvc.perform(delete("/api/comment/{commentId}/delete", commentId))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Neplatné ID komentáře"));
    }
}
