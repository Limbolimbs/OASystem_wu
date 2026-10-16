package com.example.demo.controller;

import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.server.ResponseStatusException;

import com.example.demo.entity.User;
import com.example.demo.service.UserService;
import com.example.demo.service.DepartmentService;
import com.example.demo.security.SystemRole;
import com.example.demo.security.AccountRules;

import jakarta.servlet.http.HttpSession;

@Controller
@RequestMapping("/users")
public class UserController {

    private final UserService userService;
    private final DepartmentService departmentService;

    public UserController(UserService userService, DepartmentService departmentService) {
        this.userService = userService;
        this.departmentService = departmentService;
    }

    /**
     * ユーザー一覧画面
     */
    @GetMapping
    public String list(
            Model model,
            HttpSession session,
            RedirectAttributes redirectAttributes,
            @RequestParam(required = false) String fragment) {

        if (fragment != null) {
            requireAdmin(session);
        } else if (!isAdmin(session)) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "この画面を表示する権限がありません。"
            );
            return "redirect:/home";
        }

        String view = AjaxResult.view("users", fragment, "employee-data", "employee-count");
        model.addAttribute("users", userService.findAll());
        model.addAttribute("departments", departmentService.activeDepartments());
        return view;
    }

    /**
     * ユーザー新規登録画面
     */
    @GetMapping("/new")
    public String showCreateForm(
            Model model,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        if (!isAdmin(session)) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "ユーザーを登録する権限がありません。"
            );
            return "redirect:/home";
        }

        model.addAttribute("user", new User());
        model.addAttribute("departments", departmentService.activeDepartments());
        model.addAttribute("passwordRequirements", AccountRules.PASSWORD_REQUIREMENTS);
        model.addAttribute("passwordMinLength", AccountRules.PASSWORD_MIN_LENGTH);
        model.addAttribute("passwordMaxLength", AccountRules.PASSWORD_MAX_LENGTH);
        model.addAttribute("passwordMinCategories", AccountRules.PASSWORD_MIN_CATEGORIES);
        return "user-form";
    }

    /**
     * ユーザーを登録する
     */
    @PostMapping("/save")
    public ResponseEntity<AjaxResult> save(
            @ModelAttribute User user,
            @RequestParam(defaultValue = "") String passwordConfirmation,
            HttpSession session) {
        requireAdmin(session);

        try {
            AccountRules.validateLoginId(user.getUsername());
            AccountRules.validateNewPassword(user.getPassword());
            AccountRules.validatePasswordConfirmation(user.getPassword(), passwordConfirmation);
        } catch (IllegalArgumentException exception) {
            return AjaxResult.error(HttpStatus.BAD_REQUEST, exception.getMessage());
        }

        if (user.getUsername() == null || user.getUsername().isBlank()
                || user.getPassword() == null || user.getPassword().isBlank()
                || user.getRealName() == null || user.getRealName().isBlank()
                || user.getRealName().trim().codePointCount(0, user.getRealName().trim().length()) > 50
                || user.getRole() == null
                || !user.getRole().equals(SystemRole.fromCode(user.getRole()).name())) {
            return AjaxResult.error(HttpStatus.BAD_REQUEST, "必須項目または権限を確認してください。");
        }

        if (userService.existsByUsername(user.getUsername().trim())) {
            return AjaxResult.error(HttpStatus.CONFLICT, "この社員番号（ログインID）はすでに使用されています。無効なアカウントのIDも再利用できません。");
        }

        // フォームからID・状態・作成日時を受け取らず、新規レコードだけを作成する。
        User newUser = new User();
        newUser.setUsername(user.getUsername().trim());
        newUser.setPassword(user.getPassword());
        newUser.setRealName(user.getRealName().trim());
        newUser.setDepartment(user.getDepartment() == null ? "" : user.getDepartment().trim());
        if (!newUser.getDepartment().isBlank()) departmentService.requireActive(newUser.getDepartment());
        newUser.setRole(user.getRole());
        newUser.setStatus(1);
        userService.save(newUser);

        return AjaxResult.ok("ユーザーを登録しました。従業員一覧から確認できます。");
    }

    /**
     * ユーザーを無効化する
     */
    @PostMapping("/{id}/deactivate")
    public ResponseEntity<AjaxResult> deactivate(
            @PathVariable Long id,
            HttpSession session) {
        User actor = requireAdmin(session);
        if (Objects.equals(actor.getId(), id)) {
            return AjaxResult.error(HttpStatus.BAD_REQUEST, "ログイン中のユーザーは無効化できません。");
        }

        boolean result = userService.deactivate(id);

        if (result) {
            return AjaxResult.ok("ユーザーを無効化しました。");
        } else {
            return AjaxResult.error(HttpStatus.NOT_FOUND, "対象ユーザーが見つかりません。");
        }
    }

    /**
     * ユーザーを再有効化する
     */
    @PostMapping("/{id}/activate")
    public ResponseEntity<AjaxResult> activate(
            @PathVariable Long id,
            HttpSession session) {
        requireAdmin(session);

        boolean result = userService.activate(id);

        if (result) {
            return AjaxResult.ok("ユーザーを有効化しました。");
        } else {
            return AjaxResult.error(HttpStatus.NOT_FOUND, "対象ユーザーが見つかりません。");
        }
    }

    @PostMapping("/{id}/role")
    public ResponseEntity<AjaxResult> updateRole(@PathVariable Long id,
                             @RequestParam SystemRole role,
                             HttpSession session) {
        User actor = requireAdmin(session);
        if (Objects.equals(actor.getId(), id)) {
            return AjaxResult.error(HttpStatus.BAD_REQUEST, "自分の権限は変更できません。");
        }
        userService.updateRole(id, role);
        return AjaxResult.ok("権限を更新しました。");
    }

    @PostMapping("/{id}/department")
    public ResponseEntity<AjaxResult> updateDepartment(@PathVariable Long id,
            @RequestParam String department, HttpSession session) {
        requireAdmin(session);
        departmentService.assignEmployee(id, department);
        return AjaxResult.ok("所属部署を更新しました。次回の画面アクセスから新しい部署の権限が適用されます。");
    }

    /**
     * 管理者権限を確認する
     */
    private boolean isAdmin(HttpSession session) {
        try {
            requireAdmin(session);
            return true;
        } catch (ResponseStatusException ex) {
            return false;
        }
    }

    private User requireAdmin(HttpSession session) {
        Object userId = session.getAttribute("loginUserId");
        if (!(userId instanceof Long id)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }

        User user = userService.findById(id)
                .filter(candidate -> candidate.isActive())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        if (!"ADMIN".equals(user.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return user;
    }
}
