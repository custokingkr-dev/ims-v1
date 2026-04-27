package com.custoking.ims;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class CustokingImsApplication {
    public static void main(String[] args) {
        SpringApplication.run(CustokingImsApplication.class, args);
    }
}
