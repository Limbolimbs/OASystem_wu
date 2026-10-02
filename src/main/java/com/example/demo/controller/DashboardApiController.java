package com.example.demo.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.example.demo.entity.User;
import com.example.demo.service.DashboardService;
import com.example.demo.service.UserService;

import jakarta.servlet.http.HttpSession;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardApiController {
    
	private final DashboardService dashboardService;
	private final UserService userService;

	public DashboardApiController(DashboardService dashboardService, UserService userService) {
		this.dashboardService = dashboardService;
		this.userService = userService;
	}
	
	@GetMapping
	public ResponseEntity<Map<String,Object>> getDashBoard(HttpSession session){
		Object userId = session.getAttribute("loginUserId");
		if (!(userId instanceof Long id)) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
		}

		User user = userService.findById(id).orElse(null);
		if (user == null || !Integer.valueOf(1).equals(user.getStatus())) {
			session.invalidate();
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
		}
		if (!"ADMIN".equals(user.getRole())) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}

		return ResponseEntity.ok(dashboardService.getDashboardData());
	}
}
