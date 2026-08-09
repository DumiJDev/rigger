package io.rigger.provisioner.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Auto-configuration entry point for the provisioner module.
 * Registers all provisioner beans via component scan.
 */
@AutoConfiguration
@ComponentScan(basePackages = "io.rigger.provisioner")
public class ProvisionerAutoConfiguration {}
