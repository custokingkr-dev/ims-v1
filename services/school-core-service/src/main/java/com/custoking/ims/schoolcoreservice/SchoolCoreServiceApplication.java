package com.custoking.ims.schoolcoreservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SchoolCoreServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SchoolCoreServiceApplication.class, args);
    }
}
