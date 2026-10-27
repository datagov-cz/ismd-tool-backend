package com.dia.ismdtoolbackend.ofn;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OfnConfiguration {

    @Bean
    public OfnSpecification ofnSpecification() {
        return OfnSpecification.bundled();
    }
}
