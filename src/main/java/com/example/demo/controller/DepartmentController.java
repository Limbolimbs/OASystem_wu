package com.example.demo.controller;

import com.example.demo.security.AccessControlService;
import com.example.demo.service.DepartmentService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

@Controller
@RequestMapping("/departments")
public class DepartmentController {
    private final DepartmentService departments;
    private final AccessControlService access;

    public DepartmentController(DepartmentService departments, AccessControlService access) {
        this.departments = departments;
        this.access = access;
    }

    @GetMapping
    public String index(Model model, HttpSession session, @RequestParam(required = false) String fragment) {
        requireAdmin(session);
        String view = AjaxResult.view("departments", fragment, "department-data");
        model.addAttribute("departments", departments.findAll());
        model.addAttribute("isAdmin", true);
        model.addAttribute("salesAccess", true);
        return view;
    }

    @PostMapping
    public ResponseEntity<AjaxResult> create(@RequestParam String code, @RequestParam String name,
            @RequestParam(defaultValue = "false") boolean salesEnabled, HttpSession session) {
        requireAdmin(session);
        departments.create(code, name, salesEnabled);
        return AjaxResult.ok("部署を登録しました。従業員の所属先として選択できます。");
    }

    @PostMapping("/{id}")
    public ResponseEntity<AjaxResult> update(@PathVariable Long id, @RequestParam Long version,
            @RequestParam(defaultValue = "false") boolean salesEnabled, @RequestParam Integer status,
            HttpSession session) {
        requireAdmin(session);
        departments.update(id, version, salesEnabled, status);
        return AjaxResult.ok("部署の利用設定を更新しました。");
    }

    private void requireAdmin(HttpSession session) {
        var actor = access.currentUser(session)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        if (!access.isAdmin(actor)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
}
