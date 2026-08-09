package io.rigger.operator.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableScheduling;
import io.rigger.operator.autoscaler.MetricsSource;

/**
 * Auto-configuration for the Rigger operator.
 * Enables Spring @Scheduled for the reconciliation loop and HPA controller.
 */
@AutoConfiguration
@EnableScheduling
@ComponentScan(basePackages = "io.rigger.operator")
public class OperatorAutoConfiguration {

    /** Registers the stub MetricsSource if no other implementation is provided. */
    @Bean
    @ConditionalOnMissingBean(MetricsSource.class)
    public MetricsSource stubMetricsSource() {
        return MetricsSource.STUB;
    }
}
