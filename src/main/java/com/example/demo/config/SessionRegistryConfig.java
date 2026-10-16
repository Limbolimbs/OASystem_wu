package com.example.demo.config;

import com.example.security.SessionRegistry;
import com.example.demo.security.SecuritySettings;
import java.time.Clock;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class SessionRegistryConfig {
    @Bean SessionRegistry registeredSessions(JdbcTemplate jdbc,PlatformTransactionManager manager,Clock clock,SecuritySettings settings,@Value("${oa.security.session-node:}")String node){
        return new SessionRegistry(jdbc,manager,clock,"OA",SessionRegistry.OA_OWNER,node,settings.getIdleTimeout(),settings.getAbsoluteTimeout());
    }
    @Bean org.springframework.boot.ApplicationRunner sessionRegistryReady(SessionRegistry registry){return args->registry.initialize();}
}
