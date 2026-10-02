package com.example.demo.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import com.example.demo.entity.User;
import com.example.demo.service.UserService;

import jakarta.servlet.http.HttpSession;



@Controller
public class HomeController {
	private final UserService userService;

	public HomeController(UserService userService) {
		this.userService = userService;
	}

	// 初回アクセス時にログイン画面を表示する
	@GetMapping("/")
	public String loginPage() {
		return "log-in";
	}
	
	// ログイン成功後にホーム画面を表示する
    @GetMapping("/home")
    public String homePage(HttpSession session) {
		// ユーザーの有効状態と権限を毎回確認する
		Object userId = session.getAttribute("loginUserId");
		if (!(userId instanceof Long id)) {
			return "redirect:/";
		}

		User user = userService.findById(id).orElse(null);
		if (user == null || !Integer.valueOf(1).equals(user.getStatus())) {
			session.invalidate();
			return "redirect:/";
		}

		if (!"ADMIN".equals(user.getRole())) {
			return "redirect:/attendance";
		}

		return "home";
    }
    
    @GetMapping("/logout")
    public String logout(HttpSession session) {
    	session.invalidate();
    	
    	return "redirect:/";
    }
}
