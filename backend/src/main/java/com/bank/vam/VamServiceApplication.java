package com.bank.vam;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.Contact;

@SpringBootApplication
@EnableJpaAuditing
@EnableAsync
@EnableScheduling
@OpenAPIDefinition(
    info = @Info(
        title = "VAM - Virtual Account Management API",
        version = "2.0.0",
        description = "Corporate Digital Banking Platform with Treasury Management",
        contact = @Contact(
            name = "VAM Support",
            email = "support@vam.bank"
        )
    )
)
public class VamServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(VamServiceApplication.class, args);
    }
}