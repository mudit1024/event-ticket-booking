package com.eventticket.booking.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI bookingOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Booking Service API")
                        .description("Workflow orchestration and booking lifecycle management for the Event Ticket Booking system.")
                        .version("0.1.0")
                        .contact(new Contact().name("Gairik")));
    }
}
