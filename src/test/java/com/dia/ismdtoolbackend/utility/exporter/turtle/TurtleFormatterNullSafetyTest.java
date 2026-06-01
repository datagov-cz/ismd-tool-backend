package com.dia.ismdtoolbackend.utility.exporter.turtle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.dia.ismdtoolbackend.exception.TurtleExportException;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TurtleFormatterUtil - Null Input Safety")
class TurtleFormatterNullSafetyTest {

    @Test
    @DisplayName("transformToOFNFormat throws TurtleExportException on null model")
    void nullModel_throwsTurtleExportException() {
        assertThrows(TurtleExportException.class,
                () -> TurtleFormatterUtil.transformToOFNFormat(null));
    }
}
