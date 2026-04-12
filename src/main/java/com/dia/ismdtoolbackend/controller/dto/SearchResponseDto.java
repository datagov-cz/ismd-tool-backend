package com.dia.ismdtoolbackend.controller.dto;

import com.dia.ismdtoolbackend.enums.SearchSource;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchResponseDto {
    private List<SearchResultDto> results;
    private int returnedCount;
    private int limit;
    private int offset;
    private Map<SearchSource, SourceStatusDto> sourceStatuses;
}
