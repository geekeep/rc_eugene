package com.itsmartkit.aicoding;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AiCodingApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiCodingApplication.class, args);
    }

}
