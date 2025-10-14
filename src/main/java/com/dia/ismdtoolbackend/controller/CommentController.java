package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.controller.dto.ApiResponseDto;
import com.dia.ismdtoolbackend.models.CommentCreateModel;
import com.dia.ismdtoolbackend.models.CommentModel;
import com.dia.ismdtoolbackend.service.CommentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

import static com.dia.constants.ConverterControllerConstants.LOG_REQUEST_ID;

@RestController
@RequestMapping("/api/comment")
@RequiredArgsConstructor
@Slf4j
public class CommentController {

    private final CommentService commentService;

    @PostMapping("/post")
    public ResponseEntity<ApiResponseDto<CommentModel>> postComment(
            @RequestBody CommentCreateModel commentCreateModel,
            @RequestParam String userId
    ) {
        String requestId = UUID.randomUUID().toString();
        MDC.put(LOG_REQUEST_ID, requestId);
        log.info("Comment post request created, ontologyIri: {}, conceptIri: {}, comment: {}", commentCreateModel.getOntologyIRI(), commentCreateModel.getConceptIRI(), commentCreateModel.getComment());

        try {
            if (userId == null || userId.trim().isEmpty()) {
                log.error("UserId is null or empty");
                return ResponseEntity.badRequest().body(ApiResponseDto.error("ID uživatele je povinné."));
            }

            CommentModel postedComment = commentService.postComment(commentCreateModel, userId);
            log.info("Comment post successful: {}", postedComment);

            return ResponseEntity.ok().body(ApiResponseDto.success(postedComment, "Komentář úspěšně přidán."));
        } catch (Exception e) {
            log.error("Error posting comment: {}", e.getMessage());
            return ResponseEntity.badRequest().body(ApiResponseDto.error(e.getMessage()));
        }
    }

    @DeleteMapping("/{commentId}/delete")
    public ResponseEntity<ApiResponseDto<Void>> deleteComment(@PathVariable Long commentId) {
        String requestId = UUID.randomUUID().toString();
        MDC.put(LOG_REQUEST_ID, requestId);
        log.info("Comment delete requested, commentId: {}", commentId);

        try {
            commentService.deleteComment(commentId);
            log.info("Comment delete successful: {}", commentId);

            return ResponseEntity.ok(ApiResponseDto.success("Komentář úspěšně smazán."));
        } catch (com.dia.ismdtoolbackend.exception.CommentException e) {
            if (e.getMessage().contains("nebyl nalezen")) {
                log.error("Comment not found: {}", commentId);
                return ResponseEntity.status(404).body(ApiResponseDto.error(e.getMessage()));
            }
            log.error("Error deleting comment: {}", e.getMessage());
            return ResponseEntity.badRequest().body(ApiResponseDto.error(e.getMessage()));
        } catch (Exception e) {
            log.error("Unexpected error deleting comment: {}", e.getMessage());
            return ResponseEntity.status(500).body(ApiResponseDto.error("Nastala neočekávaná chyba při mazání komentáře."));
        }
    }
}
