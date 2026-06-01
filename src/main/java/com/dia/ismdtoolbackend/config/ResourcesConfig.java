package com.dia.ismdtoolbackend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.TimeUnit;

@Configuration
public class ResourcesConfig implements WebMvcConfigurer {

    /**
     * Mute common 404 errors
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/favicon.ico")
                .addResourceLocations("classpath:/static/")
                .setCachePeriod((int) TimeUnit.DAYS.toSeconds(30));

        registry.addResourceHandler("/robots.txt")
                .addResourceLocations("classpath:/static/")
                .setCachePeriod((int) TimeUnit.DAYS.toSeconds(30));
    }
}
