package com.example.demo.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import com.example.demo.entity.User;
import com.example.demo.service.UserService;

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
}
