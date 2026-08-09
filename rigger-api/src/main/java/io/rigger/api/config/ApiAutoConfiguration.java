package io.rigger.api.config;

import io.rigger.schema.ManifestSchemaValidator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

@AutoConfiguration
@ComponentScan(basePackages = "io.rigger.api")
public class ApiAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ManifestSchemaValidator.class)
    public ManifestSchemaValidator manifestSchemaValidator() {
        return new ManifestSchemaValidator();
    }
}
