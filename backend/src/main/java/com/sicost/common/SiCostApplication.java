package com.sicost.common;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.sicost")
public class SiCostApplication {
    public static void main(String[] args) {
        SpringApplication.run(SiCostApplication.class, args);
    }
}
