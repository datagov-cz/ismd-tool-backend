package com.dia.ismdtoolbackend.controller.dto;

import com.dia.ismdtoolbackend.enums.SearchSourceStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SourceStatusDto {
    private SearchSourceStatus status;
    private int returnedCount;
    private Integer totalCount;
    private String message;
}
