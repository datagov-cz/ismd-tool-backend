package com.dia.ismdtoolbackend.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * One page of a list endpoint.
 *
 * @param items      the page, in the endpoint's stable order
 * @param totalCount every item matching the filters, across all pages
 * @param limit      the requested page size
 * @param offset     the requested offset
 */
public record PageDto<T>(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<T> items,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long totalCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int limit,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int offset
) {
}
