package com.dia.ismdtoolbackend.ofn;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OfnConfiguration {

    @Bean
    public OfnSpecification ofnSpecification() {
        return OfnSpecification.bundled();
    }

    /** Keeps the ISMD role markers in {@code typ} (D4: kept until the DIA data review). */
    @Bean
    public OfnConformanceGate ofnConformanceGate(OfnSpecification specification) {
        return new OfnConformanceGate(specification, OfnIris.MARKERS);
    }

    @Bean
    public OfnRdfReader ofnRdfReader(OfnSpecification specification) {
        return new OfnRdfReader(specification, OfnIris.MARKERS);
    }
}
