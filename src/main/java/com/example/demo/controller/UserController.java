package com.example.demo.controller;

import java.util.Objects;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.demo.entity.User;
import com.example.demo.service.UserService;
import com.example.demo.security.SystemRole;

import jakarta.servlet.http.HttpSession;

@Controller
@RequestMapping("/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * ユーザー一覧画面
     */
    @GetMapping
    public String list(
            Model model,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        if (!isAdmin(session)) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "この画面を表示する権限がありません。"
            );
            return "redirect:/home";
        }

        model.addAttribute("users", userService.findAll());
        return "users";
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
        return "user-form";
    }

    /**
     * ユーザーを登録する
     */
    @PostMapping("/save")
    public String save(
            @ModelAttribute User user,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        if (!isAdmin(session)) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "ユーザーを登録する権限がありません。"
            );
            return "redirect:/home";
        }

        if (user.getUsername() == null || user.getUsername().isBlank()
                || user.getPassword() == null || user.getPassword().isBlank()
                || user.getRealName() == null || user.getRealName().isBlank()
                || user.getRole() == null
                || !user.getRole().equals(SystemRole.fromCode(user.getRole()).name())) {
            redirectAttributes.addFlashAttribute("errorMessage", "必須項目または権限を確認してください。");
            return "redirect:/users/new";
        }

        if (userService.existsByUsername(user.getUsername().trim())) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "このユーザー名はすでに使用されています。"
            );
            return "redirect:/users/new";
        }

        // フォームからID・状態・作成日時を受け取らず、新規レコードだけを作成する。
        User newUser = new User();
        newUser.setUsername(user.getUsername().trim());
        newUser.setPassword(user.getPassword());
        newUser.setRealName(user.getRealName().trim());
        newUser.setDepartment(user.getDepartment() == null ? "" : user.getDepartment().trim());
        newUser.setRole(user.getRole());
        newUser.setStatus(1);
        userService.save(newUser);

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "ユーザーを登録しました。"
        );

        return "redirect:/users";
    }

    /**
     * ユーザーを無効化する
     */
    @PostMapping("/{id}/deactivate")
    public String deactivate(
            @PathVariable Long id,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        if (!isAdmin(session)) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "ユーザーを無効化する権限がありません。"
            );
            return "redirect:/home";
        }

        Long loginUserId = (Long) session.getAttribute("loginUserId");

        if (Objects.equals(loginUserId, id)) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "ログイン中のユーザーは無効化できません。"
            );
            return "redirect:/users";
        }

        boolean result = userService.deactivate(id);

        if (result) {
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "ユーザーを無効化しました。"
            );
        } else {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "対象ユーザーが見つかりません。"
            );
        }

        return "redirect:/users";
    }

    /**
     * ユーザーを再有効化する
     */
    @PostMapping("/{id}/activate")
    public String activate(
            @PathVariable Long id,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        if (!isAdmin(session)) {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "ユーザーを有効化する権限がありません。"
            );
            return "redirect:/home";
        }

        boolean result = userService.activate(id);

        if (result) {
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "ユーザーを有効化しました。"
            );
        } else {
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "対象ユーザーが見つかりません。"
            );
        }

        return "redirect:/users";
    }

    @PostMapping("/{id}/role")
    public String updateRole(@PathVariable Long id,
                             @RequestParam SystemRole role,
                             HttpSession session,
                             RedirectAttributes flash) {
        if (!isAdmin(session)) {
            flash.addFlashAttribute("errorMessage", "権限を変更する権限がありません。");
            return "redirect:/attendance";
        }
        if (Objects.equals(session.getAttribute("loginUserId"), id)) {
            flash.addFlashAttribute("errorMessage", "自分の権限は変更できません。");
            return "redirect:/users";
        }
        try {
            userService.updateRole(id, role);
            flash.addFlashAttribute("successMessage", "権限を更新しました。");
        } catch (IllegalArgumentException | IllegalStateException ex) {
            flash.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/users";
    }

    /**
     * ログイン処理
     */
    @PostMapping("/login")
    @ResponseBody
    public String login(
            @RequestParam("userName") String userName,
            @RequestParam("password") String password,
            HttpSession session) {

        if (userName == null || userName.isBlank()
                || password == null || password.isBlank()) {
            return "NG";
        }

        User loginUser = userService.findLoginUser(
                userName.trim(),
                password
        );

        if (loginUser == null) {
            return "NG";
        }

        session.setAttribute("loginUserId", loginUser.getId());
        session.setAttribute("loginUser", loginUser.getUsername());
        session.setAttribute("loginRealName", loginUser.getRealName());
        session.setAttribute("loginRole", loginUser.getRole());
        session.setAttribute(
                "loginDepartment",
                loginUser.getDepartment()
        );

        return "OK";
    }

    /**
     * 管理者権限を確認する
     */
    private boolean isAdmin(HttpSession session) {
        Object userId = session.getAttribute("loginUserId");
        if (!(userId instanceof Long id)) {
            return false;
        }

        return userService.findById(id)
                .filter(user -> Integer.valueOf(1).equals(user.getStatus()))
                .filter(user -> "ADMIN".equals(user.getRole()))
                .isPresent();
    }
}
