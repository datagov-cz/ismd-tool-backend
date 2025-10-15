package com.dia.ismdtoolbackend.config;

import com.dia.ismdtoolbackend.utility.exporter.json.JsonExporter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ExporterConfiguration {

    @Bean
    public JsonExporter jsonExporter() {
        return new JsonExporter();
    }
}
