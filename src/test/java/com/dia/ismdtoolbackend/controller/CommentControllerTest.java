package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.exception.CommentException;
import com.dia.ismdtoolbackend.models.CommentModel;
import com.dia.ismdtoolbackend.service.CommentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class CommentControllerTest {

    private MockMvc mockMvc;

    @Mock
    private CommentService commentService;

    @InjectMocks
    private CommentController commentController;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(commentController).build();
    }

    // ========== Post Comment Tests ==========

    @Test
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
                        .content(jsonRequest)
                        .param("userId", userId))
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
    void testPostComment_EmptyUserId() throws Exception {
        String jsonRequest = """
                {
                    "ontologyIRI": "http://example.org/ontology",
                    "conceptIRI": "http://example.org/concept",
                    "comment": "This is a test comment"
                }
                """;

        mockMvc.perform(post("/api/comment/post")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest)
                        .param("userId", ""))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("ID uživatele je povinné."));
    }

    @Test
    void testPostComment_NullUserId() throws Exception {
        String jsonRequest = """
                {
                    "ontologyIRI": "http://example.org/ontology",
                    "conceptIRI": "http://example.org/concept",
                    "comment": "This is a test comment"
                }
                """;

        mockMvc.perform(post("/api/comment/post")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isBadRequest());
    }

    @Test
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
                .thenThrow(new RuntimeException("Error posting comment"));

        mockMvc.perform(post("/api/comment/post")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest)
                        .param("userId", userId))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Error posting comment"));
    }

    @Test
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
                .thenThrow(new RuntimeException("Komentář nesmí být prázdný"));

        mockMvc.perform(post("/api/comment/post")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest)
                        .param("userId", userId))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Komentář nesmí být prázdný"));
    }

    @Test
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
                .thenThrow(new RuntimeException("Neplatné IRI slovníku"));

        mockMvc.perform(post("/api/comment/post")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest)
                        .param("userId", userId))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Neplatné IRI slovníku"));
    }

    @Test
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
                        .content(jsonRequest)
                        .param("userId", userId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.comment").value(longComment))
                .andExpect(jsonPath("$.message").value("Komentář úspěšně přidán."));
    }

    // ========== Delete Comment Tests ==========

    @Test
    void testDeleteComment_Success() throws Exception {
        Long commentId = 1L;

        doNothing().when(commentService).deleteComment(commentId);

        mockMvc.perform(delete("/api/comment/{commentId}/delete", commentId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Komentář úspěšně smazán."));
    }

    @Test
    void testDeleteComment_NotFound() throws Exception {
        Long commentId = 999L;

        doThrow(new CommentException("Komentář s ID 999 nebyl nalezen"))
                .when(commentService).deleteComment(commentId);

        mockMvc.perform(delete("/api/comment/{commentId}/delete", commentId))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Komentář s ID 999 nebyl nalezen"));
    }

    @Test
    void testDeleteComment_CommentException() throws Exception {
        Long commentId = 1L;

        doThrow(new CommentException("Chyba při mazání komentáře"))
                .when(commentService).deleteComment(commentId);

        mockMvc.perform(delete("/api/comment/{commentId}/delete", commentId))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Chyba při mazání komentáře"));
    }

    @Test
    void testDeleteComment_UnexpectedException() throws Exception {
        Long commentId = 1L;

        doThrow(new RuntimeException("Neočekávaná chyba"))
                .when(commentService).deleteComment(commentId);

        mockMvc.perform(delete("/api/comment/{commentId}/delete", commentId))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Nastala neočekávaná chyba při mazání komentáře."));
    }

    @Test
    void testDeleteComment_InvalidCommentId() throws Exception {
        Long commentId = -1L;

        doThrow(new CommentException("Neplatné ID komentáře"))
                .when(commentService).deleteComment(commentId);

        mockMvc.perform(delete("/api/comment/{commentId}/delete", commentId))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value("Neplatné ID komentáře"));
    }
}
