package com.bank.vam.defectfix;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DefectFixServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(DefectFixServiceApplication.class, args);
    }
}
