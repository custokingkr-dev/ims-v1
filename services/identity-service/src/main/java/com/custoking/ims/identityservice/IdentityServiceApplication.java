package com.custoking.ims.identityservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// Scheduling drives SessionActivityHealthReporter, which is how live session counts reach Cloud
// Monitoring. Without this the component is constructed but never fires, and the metric stays empty
// with no error anywhere.
@EnableScheduling
@SpringBootApplication
public class IdentityServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(IdentityServiceApplication.class, args);
    }
}
