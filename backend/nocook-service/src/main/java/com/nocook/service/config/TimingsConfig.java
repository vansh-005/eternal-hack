package com.nocook.service.config;

import com.nocook.domain.model.Timings;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Binds {@code nocook.timing.*} into the domain {@link Timings} bean used by every worker and service. */
@Configuration
@EnableConfigurationProperties(TimingsConfig.TimingProperties.class)
public class TimingsConfig {

    @ConfigurationProperties("nocook.timing")
    public record TimingProperties(Duration l1, Duration ivr, Duration maxAge, Duration deferBy) {}

    @Bean
    public Timings timings(TimingProperties properties) {
        return new Timings(properties.l1(), properties.ivr(), properties.maxAge(), properties.deferBy());
    }
}
