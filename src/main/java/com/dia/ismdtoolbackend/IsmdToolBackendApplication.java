package com.dia.ismdtoolbackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@EnableJpaAuditing
public class IsmdToolBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(IsmdToolBackendApplication.class, args);
    }

}
