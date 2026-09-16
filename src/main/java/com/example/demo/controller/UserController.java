package com.example.demo.controller;

import java.util.Optional;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.example.demo.entity.User;
import com.example.demo.service.UserService;

import jakarta.servlet.http.HttpSession;

@Controller
@RequestMapping("/users")
public class UserController {

	private final UserService userService;
	
	public UserController(UserService userService) {
		this.userService = userService;
	}
	
	@GetMapping
	public String liset(Model model) {
		model.addAttribute("users",userService.findAll());
		return "users";
	}
	
	@GetMapping("/new")
	public String showCreateForm(Model model) {
		model.addAttribute("user",new User());
		return "user-form";
	}
	
	@PostMapping("/save")
	public String save(@ModelAttribute User user) {
		user.setStatus(1);
		userService.save(user);
		return "redirect:/users";
	}
	
	@PostMapping("/login")
	@ResponseBody
	public String logIn(@RequestParam String userName,@RequestParam String password,Model model,HttpSession session) {
		
		User loginUser = userService.findLoginUser(userName.trim(), password);
		
		if(loginUser == null) {
			return "NG";
		}
		
		String realName = loginUser.getRealName();
		
		//如果没有填写realname，显示登陆账号
		if(realName == null || realName.isBlank()) {
			realName = loginUser.getUsername();
		}
		//拦截器使用的登陆状态
		session.setAttribute("loginUser", loginUser.getUsername());
		//页面右上角显示真实姓名
		session.setAttribute("loginRealName", realName);
		
		return "OK";
		
	}
}
