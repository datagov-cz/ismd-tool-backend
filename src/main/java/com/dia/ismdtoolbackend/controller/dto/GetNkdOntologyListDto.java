package com.dia.ismdtoolbackend.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GetNkdOntologyListDto {
    private List<NkdOntologyListItemDto> ontologies;
}
