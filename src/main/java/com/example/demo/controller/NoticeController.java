package com.example.demo.controller;

import com.example.demo.entity.User;
import com.example.demo.notice.NoticeForm;
import com.example.demo.security.AccessControlService;
import com.example.demo.service.NoticeService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Controller
@RequestMapping("/notices")
public class NoticeController {
    private final AccessControlService access;
    private final NoticeService notices;
    public NoticeController(AccessControlService access, NoticeService notices) { this.access = access; this.notices = notices; }

    @GetMapping
    public String index(@RequestParam(defaultValue = "") String keyword, @RequestParam(defaultValue = "ALL") String filter,
                        @RequestParam(defaultValue = "0") int page, @RequestParam(required = false) String fragment,
                        HttpSession session, Model model) {
        User actor = access.currentUser(session).orElse(null);
        if (actor == null) {
            if (fragment != null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
            return "redirect:/";
        }
        String view = AjaxResult.view("notices", fragment, "notice-data", "notice-count");
        var rows = notices.list(actor, keyword, filter, page);
        model.addAttribute("user", actor); model.addAttribute("admin", access.isAdmin(actor));
        model.addAttribute("salesAccess", access.canManageSales(actor)); model.addAttribute("canEdit", notices.canEdit(actor));
        model.addAttribute("rows", rows); model.addAttribute("keyword", keyword.trim()); model.addAttribute("filter", filter);
        model.addAttribute("unreadCount", notices.unreadCount(actor));
        return view;
    }

    @GetMapping("/{id}") @ResponseBody
    public NoticeService.Detail detail(@PathVariable Long id, HttpSession session) { return notices.detail(requireUser(session), id); }

    @PostMapping("/save")
    public ResponseEntity<AjaxResult> save(@ModelAttribute NoticeForm form, HttpSession session) {
        User actor = requireUser(session); notices.save(actor, form);
        return AjaxResult.ok("お知らせを保存しました。");
    }
    @PostMapping("/{id}/deactivate")
    public ResponseEntity<AjaxResult> deactivate(@PathVariable Long id, @RequestParam Long version, HttpSession session) {
        notices.deactivate(requireUser(session), id, version);
        return AjaxResult.ok("お知らせを無効化しました。記録は保持されています。");
    }
    @PostMapping("/{id}/restore")
    public ResponseEntity<AjaxResult> restore(@PathVariable Long id, @RequestParam Long version, HttpSession session) {
        notices.restore(requireUser(session), id, version);
        return AjaxResult.ok("下書きとして復元しました。内容を確認してから公開してください。");
    }
    @PostMapping("/{id}/read")
    public ResponseEntity<AjaxResult> read(@PathVariable Long id, @RequestParam Long version, HttpSession session) {
        notices.markRead(requireUser(session), id, version);
        return AjaxResult.ok("既読にしました。");
    }
    private User requireUser(HttpSession session) {
        return access.currentUser(session).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }
}
