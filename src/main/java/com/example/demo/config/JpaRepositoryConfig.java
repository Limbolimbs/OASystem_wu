package com.example.demo.config;

import com.example.demo.repository.LogicalDeleteJpaRepository;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@EnableJpaRepositories(basePackages = "com.example.demo.repository", repositoryBaseClass = LogicalDeleteJpaRepository.class)
public class JpaRepositoryConfig { }
