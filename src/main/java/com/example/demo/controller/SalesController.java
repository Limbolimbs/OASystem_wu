package com.example.demo.controller;

import java.time.YearMonth;
import java.util.Map;
import com.example.demo.attendance.AttendanceClock;
import com.example.demo.entity.User;
import com.example.demo.sales.SalesForm;
import com.example.demo.security.AccessControlService;
import com.example.demo.service.DepartmentService;
import com.example.demo.service.SalesService;
import jakarta.servlet.http.HttpSession;
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
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;

@Controller
@RequestMapping("/sales")
public class SalesController {
    private final AccessControlService access;
    private final DepartmentService departments;
    private final SalesService sales;

    public SalesController(AccessControlService access, DepartmentService departments, SalesService sales) {
        this.access = access;
        this.departments = departments;
        this.sales = sales;
    }

    @GetMapping
    public String index(Model model, HttpSession session, @RequestParam(required = false) String fragment) {
        User actor = requireUser(session);
        String view = AjaxResult.view("sales", fragment, "sales-data");
        model.addAttribute("currentUser", actor);
        model.addAttribute("isAdmin", access.isAdmin(actor));
        model.addAttribute("salesAccess", true);
        model.addAttribute("salesRows", sales.findVisible(actor));
        if (fragment == null) {
            model.addAttribute("departments", departments.activeDepartments());
            model.addAttribute("currentMonth", YearMonth.from(AttendanceClock.now()).toString());
        }
        return view;
    }

    @GetMapping("/{id}")
    @ResponseBody
    public Map<String, Object> detail(@PathVariable Long id, HttpSession session) {
        var row = sales.findEditable(requireUser(session), id);
        // Entity全体を返さず、編集に必要な項目のみを返す。
        return Map.of("id", row.getId(), "version", row.getVersion(), "salesMonth", row.getSalesMonth(),
                "department", row.getDepartment(), "salesAmount", row.getSalesAmount(),
                "orderCount", row.getOrderCount(), "customerCount", row.getCustomerCount(),
                "notes", row.getNotes() == null ? "" : row.getNotes());
    }

    @PostMapping("/save")
    public ResponseEntity<AjaxResult> save(@ModelAttribute SalesForm form, HttpSession session) {
        sales.save(requireUser(session), form);
        return AjaxResult.ok("営業実績を保存しました。経営ダッシュボードの集計に反映されます。");
    }

    private User requireUser(HttpSession session) {
        User actor = access.currentUser(session)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        if (!access.canManageSales(actor)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return actor;
    }
}
