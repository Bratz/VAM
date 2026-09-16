package com.bank.vam.fileingest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAsync
public class FileIngestServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FileIngestServiceApplication.class, args);
    }
}
