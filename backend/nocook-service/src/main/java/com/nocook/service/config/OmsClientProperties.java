package com.nocook.service.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** {@code nocook.oms.*}: base URL and the hard read budget (API.md section 2: 300 ms connect, 800 ms read). */
@Configuration
@EnableConfigurationProperties(OmsClientProperties.Values.class)
public class OmsClientProperties {

    @ConfigurationProperties("nocook.oms")
    public record Values(String baseUrl, Duration connectTimeout, Duration readTimeout) {}
}
