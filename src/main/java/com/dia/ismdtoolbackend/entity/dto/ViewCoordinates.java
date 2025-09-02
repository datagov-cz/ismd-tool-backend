package com.dia.ismdtoolbackend.entity.dto;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Data
@Getter
@Setter
public class ViewCoordinates {
    private double x;
    private double y;
    private double zoom;
    private Map<String, Object> additionalViewData;
}
