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
	//@ResponseBody
	public String logIn(@RequestParam String userName,@RequestParam String password) {
//		if("admin".equals(userName)&&"password".equals(password)) {
//			return "index";
//		}else {
//			return "NG";
//		}
		int count = userService.findOne(userName, password);
		if(count > 0) {
			return "index";
		}else {
			return "user-form";
		}
	}
}
