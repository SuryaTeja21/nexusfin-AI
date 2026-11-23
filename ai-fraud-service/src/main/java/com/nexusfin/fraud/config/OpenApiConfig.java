package com.nexusfin.fraud.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("NexusFin AI - Fraud Detection & Audit Intelligence Service")
                        .version("1.0.0")
                        .description("Real-Time Dual LLM & Rule-Based Fraud Detection with Dead-Letter Topic (DLT) Quarantine & Replay"))
                .addSecurityItem(new SecurityRequirement().addList("BearerAuth"))
                .components(new Components()
                        .addSecuritySchemes("BearerAuth", new SecurityScheme()
                                .name("BearerAuth")
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Keycloak JWT Bearer Token (obtain via POST http://localhost:8083/realms/nexusfin/protocol/openid-connect/token)")));
    }
}
