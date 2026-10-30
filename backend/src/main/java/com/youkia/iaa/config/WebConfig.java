package com.youkia.iaa.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final String[] origins;
    public WebConfig(@Value("${APP_CORS_ALLOWED_ORIGINS:http://localhost:5173,http://127.0.0.1:5173}") String origins){this.origins=origins.split(",");}
    @Override public void addCorsMappings(CorsRegistry r){r.addMapping("/api/**").allowedOrigins(origins).allowCredentials(true).allowedMethods("GET","POST","PATCH","PUT","OPTIONS").allowedHeaders("*");}
}
