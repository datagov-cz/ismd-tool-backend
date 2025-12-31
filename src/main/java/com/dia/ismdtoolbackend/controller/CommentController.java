package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.config.security.SecurityUser;
import com.dia.ismdtoolbackend.controller.dto.ApiResponseDto;
import com.dia.ismdtoolbackend.models.CommentCreateModel;
import com.dia.ismdtoolbackend.models.CommentModel;
import com.dia.ismdtoolbackend.service.CommentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

import static com.dia.constants.FormatConstants.Converter.LOG_REQUEST_ID;

@RestController
@RequestMapping("/api/comment")
@RequiredArgsConstructor
@Slf4j
public class CommentController {

    private final CommentService commentService;

    @PostMapping("/post")
    public ResponseEntity<ApiResponseDto<CommentModel>> postComment(
            @RequestBody CommentCreateModel commentCreateModel,
            @AuthenticationPrincipal SecurityUser securityUser
    ) {
        String requestId = UUID.randomUUID().toString();
        MDC.put(LOG_REQUEST_ID, requestId);
        log.info("Comment post request created, ontologyIri: {}, conceptIri: {}, userId: {}, comment: {}", commentCreateModel.getOntologyIRI(), commentCreateModel.getConceptIRI(), securityUser.getUserId(), commentCreateModel.getComment());

        CommentModel postedComment = commentService.postComment(commentCreateModel, securityUser.getUserId());
        log.info("Comment post successful: {}", postedComment);

        return ResponseEntity.ok().body(ApiResponseDto.success(postedComment, "Komentář úspěšně přidán."));
    }

    @DeleteMapping("/{commentId}/delete")
    @PreAuthorize("@ontologySecurityService.canModifyComment(#commentId)")
    public ResponseEntity<ApiResponseDto<Void>> deleteComment(
            @PathVariable Long commentId,
            @AuthenticationPrincipal SecurityUser securityUser
    ) {
        String requestId = UUID.randomUUID().toString();
        MDC.put(LOG_REQUEST_ID, requestId);
        log.info("Comment delete requested, commentId: {}, userId: {}", commentId, securityUser.getUserId());

        commentService.deleteComment(commentId);
        log.info("Comment delete successful: {}", commentId);

        return ResponseEntity.ok(ApiResponseDto.success("Komentář úspěšně smazán."));
    }
}
