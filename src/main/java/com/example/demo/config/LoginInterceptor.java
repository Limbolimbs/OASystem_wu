//package com.example.demo.config;
//
//import org.springframework.web.servlet.HandlerInterceptor;
//
//import jakarta.servlet.http.HttpServletRequest;
//import jakarta.servlet.http.HttpServletResponse;
//import jakarta.servlet.http.HttpSession;
//
//public class LoginInterceptor implements HandlerInterceptor{
//    @Override
//    public boolean preHandle(
//    		HttpServletRequest request,
//    		HttpServletResponse response,
//    		Object handler) throws Exception{
//    	HttpSession session = request.getSession(false);
//    	
//    	// ログイン済みの場合はアクセスを許可する
//    	if(session != null && session.getAttribute("loginUser") != null) {
//    		return true;
//    	}
//    	// 未ログインの場合はログイン画面へ戻す
//    	response.sendRedirect(request.getContextPath() + "/");
//    	return false;
//    }
//}
