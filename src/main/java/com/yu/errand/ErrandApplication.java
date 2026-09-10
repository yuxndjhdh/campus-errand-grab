package com.yu.errand;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ErrandApplication {

    public static void main(String[] args) {
        SpringApplication.run(ErrandApplication.class, args);
    }
}
