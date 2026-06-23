package com.dia.ismdtoolbackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class IsmdToolBackendApplication {

    // Release v1.0.1

    public static void main(String[] args) {
        SpringApplication.run(IsmdToolBackendApplication.class, args);
    }

}
