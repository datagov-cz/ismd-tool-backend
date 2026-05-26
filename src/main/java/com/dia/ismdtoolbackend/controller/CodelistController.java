package com.dia.ismdtoolbackend.controller;

import com.dia.ismdtoolbackend.controller.dto.ApiResponseDto;
import com.dia.ismdtoolbackend.controller.dto.DataTypeDto;
import com.dia.ismdtoolbackend.enums.PropertyDataType;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * Public reference-data endpoints for FE form dropdowns.
 * No user context, no authentication.
 */
@RestController
@RequestMapping("/api/codelist")
@RequiredArgsConstructor
@Slf4j
public class CodelistController {

    @Operation(
            summary = "Číselník datových typů pro vlastnosti",
            description = "Vrací uzavřený seznam podporovaných datových typů (v1: 9 hodnot) " +
                    "v pořadí pro zobrazení v UI. Položky obsahují stabilní {code} a " +
                    "lokalizovaný {label}; IRI se na drátu neposílá."
    )
    @GetMapping("/property-datatypes")
    public ResponseEntity<ApiResponseDto<List<DataTypeDto>>> propertyDatatypes() {
        List<DataTypeDto> items = Arrays.stream(PropertyDataType.values())
                .map(PropertyDataType::toDto)
                .toList();
        return ResponseEntity.ok(ApiResponseDto.success(items,
                "Číselník datových typů úspěšně načten."));
    }
}
