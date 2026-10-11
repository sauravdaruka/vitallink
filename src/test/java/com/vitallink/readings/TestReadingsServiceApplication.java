package com.vitallink.readings;

import org.springframework.boot.SpringApplication;

public class TestReadingsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.from(ReadingsServiceApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
