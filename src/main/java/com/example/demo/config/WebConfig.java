package com.example.demo.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer{
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
    	
    	registry.addInterceptor(new LoginInterceptor())
    	
    	//拦截所有地址
    	.addPathPatterns("/**")
    	
    	//以下地址不需要登陆
    	.excludePathPatterns(
    			"/",
    			"/users/logIn",
    			"/logout",
    			"/error",
    			"/favicon.ico",
    			"/css/**",
    			"/js/**",
    			"/images/**"
    			);
    }
}
