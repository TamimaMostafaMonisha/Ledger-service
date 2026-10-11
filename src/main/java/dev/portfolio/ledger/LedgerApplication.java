package dev.portfolio.ledger;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class LedgerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerApplication.class, args);
    }

    /** All timestamps are taken from one UTC clock so they can be controlled in tests. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
