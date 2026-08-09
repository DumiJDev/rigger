package io.rigger.gitops.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@AutoConfiguration
@EnableScheduling
@ComponentScan(basePackages = "io.rigger.gitops")
public class GitOpsAutoConfiguration {}
