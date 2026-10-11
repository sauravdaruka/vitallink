package com.vitallink.readings;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ReadingsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReadingsServiceApplication.class, args);
    }
}
