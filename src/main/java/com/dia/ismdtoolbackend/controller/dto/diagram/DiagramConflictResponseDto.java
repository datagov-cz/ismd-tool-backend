package com.dia.ismdtoolbackend.controller.dto.diagram;

import com.dia.ismdtoolbackend.controller.dto.ApiResponseDto;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The 409 body of Převzít, named as a concrete type purely so it reaches the OpenAPI schema.
 *
 * <p>The response is built as {@code ApiResponseDto<DiagramConflictDto>} by
 * {@code GlobalExceptionHandler}, never by a controller signature — and springdoc only walks controller
 * signatures, so neither the wrapper nor {@link DiagramConflictDto} would otherwise be generated and the
 * FE could not type the conflict-resolution UI. Referenced from the {@code @ApiResponse} on
 * {@code DiagramController.materialize}; it is not instantiated at runtime.
 */
@Schema(name = "ApiResponseDtoDiagramConflictDto",
        description = "Odpověď 409 s přehledem kolizí s jinými diagramy.")
public class DiagramConflictResponseDto extends ApiResponseDto<DiagramConflictDto> {
}