package com.example.control;

import com.example.security.SessionRegistry;
import java.time.*;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import jakarta.servlet.http.*;

@Configuration
public class ConsoleSessionRegistryConfig {
    @Bean SessionRegistry registeredSessions(JdbcTemplate jdbc,PlatformTransactionManager manager,Clock clock,OperatorSettings settings,@Value("${control.session-node:}")String node){
        return new SessionRegistry(jdbc,manager,clock,"CONTROL",settings.getOperatorId(),node,Duration.ofMinutes(10),Duration.ofHours(1));
    }
    @Bean org.springframework.boot.ApplicationRunner sessionRegistryReady(SessionRegistry registry){return args->registry.initialize();}
    @Bean org.springframework.boot.web.servlet.ServletListenerRegistrationBean<HttpSessionListener> sessionListener(SessionRegistry registry){
        return new org.springframework.boot.web.servlet.ServletListenerRegistrationBean<>(new HttpSessionListener(){
            @Override public void sessionDestroyed(HttpSessionEvent event){try{Object ref=event.getSession().getAttribute(SessionRegistry.REF);if(ref instanceof String value)registry.close(value,"LOCAL_END");}catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(ConsoleSessionRegistryConfig.class).error("会話終了状態の保存障害");}}
        });
    }
}
