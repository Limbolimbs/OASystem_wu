package com.example.demo.controller;

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
	
	@PostMapping("/logIn")
	public String logIn(@RequestParam(value = "userName",required = false) String userName,@RequestParam(value = "password",required = false) String password,Model model,HttpSession session) {
		
		if(userName == null || userName.isBlank() || password == null ||password.isBlank()) {
			model.addAttribute(
				"errorMessage",	
				"ユーザー名とパスワードを入力してください。"
			);
			return "log-in";
		}
		int count = userService.findOne(userName.trim(), password);
		if(count > 0) {
			//保持登陆状态
			session.setAttribute("loginUser", userName);
			//跳转主页
			return "redirect:/home";
		}
		
		model.addAttribute(
			"errorMessage",
			"ユーザー名またはパスワードが正しくありません。"
		);
		
		return "log-in";
	}
}
