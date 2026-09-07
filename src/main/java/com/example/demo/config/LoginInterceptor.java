package com.example.demo.config;

import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

public class LoginInterceptor implements HandlerInterceptor{
    @Override
    public boolean preHandle(
    		HttpServletRequest request,
    		HttpServletResponse response,
    		Object handler) throws Exception{
    	HttpSession session = request.getSession(false);
    	
    	//已经登陆，允许访问
    	if(session != null && session.getAttribute("loginUser") != null) {
    		return true;
    	}
    	//没有登陆，返回登陆界面
    	response.sendRedirect(request.getContextPath() + "/");
    	return false;
    }
}
