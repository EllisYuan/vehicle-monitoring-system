package com.vms.infrastructure;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    @Bean
    public Clock clock() {
        return Clock.system(BUSINESS_ZONE);
    }
}
