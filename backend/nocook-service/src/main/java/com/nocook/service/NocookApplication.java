package com.nocook.service;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class NocookApplication {

    public static void main(String[] args) {
        // Postgres 18 rejects legacy zone ids such as Asia/Calcutta that pgjdbc would send.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(NocookApplication.class, args);
    }
}
