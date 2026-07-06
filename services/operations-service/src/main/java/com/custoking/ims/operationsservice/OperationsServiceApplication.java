package com.custoking.ims.operationsservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class OperationsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OperationsServiceApplication.class, args);
    }
}
