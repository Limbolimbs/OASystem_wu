package com.example.demo.controller;

import com.example.demo.entity.User;
import com.example.demo.service.DepartmentService;
import com.example.demo.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {
    private final UserService userService;
    private final DepartmentService departmentService;

    public HomeController(UserService userService, DepartmentService departmentService) {
        this.userService = userService;
        this.departmentService = departmentService;
    }

    @GetMapping("/")
    public String loginPage() {
        return "log-in";
    }

    /** ログイン後の移動先はセッションの部署名ではなくDBの最新情報から決める。 */
    @GetMapping("/home")
    public String homePage(HttpSession session) {
        Object userId = session.getAttribute("loginUserId");
        if (!(userId instanceof Long id)) return "redirect:/";
        User user = userService.findById(id).orElse(null);
        if (user == null || !user.isActive()) {
            session.invalidate();
            return "redirect:/";
        }
        if ("ADMIN".equals(user.getRole())) return "home";
        return departmentService.isSalesDepartment(user.getDepartment())
                ? "redirect:/sales" : "redirect:/attendance";
    }

}
