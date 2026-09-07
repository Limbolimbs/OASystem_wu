package com.example.demo.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import jakarta.servlet.http.HttpSession;



@Controller
public class HomeController {
	
	//首次进入，登陆界面
	@GetMapping("/")
	public String loginPage() {
		return "log-in";
	}
	
	//登陆成功后进入主页
    @GetMapping("/home")
    public String homePage(HttpSession session) {
    	
    	//没有登陆成功不允许直接进入主页
    	if(session.getAttribute("loginUser")==null) {
    		return "redirect:/";
    	}
    	
    	return "home";
    }
    
    @GetMapping("/logout")
    public String logout(HttpSession session) {
    	session.invalidate();
    	
    	return "redirect:/";
    }
}
