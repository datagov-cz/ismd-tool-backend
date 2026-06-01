package com.dia.ismdtoolbackend.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GetNkdOntologyListDto {
    private List<NkdOntologyListItemDto> ontologies;

    @JsonProperty("celkový-počet")
    private Integer totalCount;

    @JsonProperty("počet-slovníků")
    private Integer ontologyCount;

    @JsonProperty("počet-pojmů")
    private Integer conceptCount;
}
