package io.rigger.swarm.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Auto-configuration for the Swarm adapter.
 * docker-java client is created by DockerClientFactory (@PostConstruct).
 * All adapter beans are registered via component scan.
 */
@AutoConfiguration
@ComponentScan(basePackages = "io.rigger.swarm")
public class SwarmAdapterAutoConfiguration {}
