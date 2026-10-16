package com.example.demo.controller;

import com.example.demo.attendance.AttendanceClock;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
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

import com.example.demo.attendance.AttendanceRequestType;
import com.example.demo.attendance.DayType;
import com.example.demo.attendance.WorkMode;
import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import com.example.demo.security.AccessControlService;
import com.example.demo.service.AttendancePolicyService;
import com.example.demo.service.AttendanceRequestService;

import jakarta.servlet.http.HttpSession;

@Controller
@RequestMapping("/attendance/manage")
public class AttendanceManagementController {

    private final AccessControlService access;
    private final AttendanceRequestService requests;
    private final AttendancePolicyService policies;
    private final UserRepository users;

    public AttendanceManagementController(AccessControlService access,
                                          AttendanceRequestService requests,
                                          AttendancePolicyService policies,
                                          UserRepository users) {
        this.access = access;
        this.requests = requests;
        this.policies = policies;
        this.users = users;
    }

    @GetMapping
    public String index(@RequestParam(defaultValue = "0") int ownPage,
                        @RequestParam(defaultValue = "0") int reviewPage,
                        @RequestParam(defaultValue = "0") int reviewedPage,
                        @RequestParam(required = false) String fragment,
                        @RequestParam(required = false)
                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate scheduleDate,
                        HttpSession session, Model model) {
        User user = access.currentUser(session).orElse(null);
        if (user == null) {
            if (fragment != null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
            return "redirect:/";
        }
        boolean admin = access.isAdmin(user);
        boolean reviewer = access.canApproveAttendance(user);
        model.addAttribute("applicant", "EMPLOYEE".equals(user.getRole()));
        String view = AjaxResult.view("attendance-manage", fragment,
                "history", "review", "reviewed", "rules", "schedule-list", "holiday-list");
        if (("review".equals(fragment) || "reviewed".equals(fragment)) && !reviewer
                || ("rules".equals(fragment) || "schedule-list".equals(fragment)
                || "holiday-list".equals(fragment)) && !admin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        model.addAttribute("user", user);
        model.addAttribute("admin", admin);
        model.addAttribute("salesAccess", access.canManageSales(user));
        model.addAttribute("reviewer", reviewer);
        model.addAttribute("requestTypes", AttendanceRequestType.values());
        if (fragment == null || "history".equals(fragment)) {
            var own = boundedPage(ownPage, value -> requests.ownRequests(user.getId(), value));
            model.addAttribute("ownRequests", own);
            model.addAttribute("ownPage", own.getNumber());
        }
        if (reviewer && (fragment == null || "review".equals(fragment))) {
            var queue = boundedPage(reviewPage, requests::reviewQueue);
            model.addAttribute("reviewRequests", queue);
            model.addAttribute("reviewPage", queue.getNumber());
        }
        if (reviewer && (fragment == null || "reviewed".equals(fragment))) {
            var reviewed = boundedPage(reviewedPage, value -> requests.reviewedBy(user.getId(), value));
            model.addAttribute("reviewedRequests", reviewed);
            model.addAttribute("reviewedPage", reviewed.getNumber());
        }
        if (admin && (fragment == null || "rules".equals(fragment))) {
            var policy = policies.currentPolicy();
            model.addAttribute("policy", policy);
            int mask = policy.getWorkDaysMask();
            model.addAttribute("workdayFlags", Map.of(
                    "mon", (mask & 1) != 0, "tue", (mask & 2) != 0,
                    "wed", (mask & 4) != 0, "thu", (mask & 8) != 0,
                    "fri", (mask & 16) != 0, "sat", (mask & 32) != 0,
                    "sun", (mask & 64) != 0));
        }
        if (admin && (fragment == null || "holiday-list".equals(fragment))) {
            model.addAttribute("holidays", policies.holidays());
        }
        if (admin && fragment == null) {
            model.addAttribute("employees", users.findByStatusOrderByIdAsc(1));
            model.addAttribute("dayTypes", DayType.values());
            model.addAttribute("workModes", WorkMode.values());
        }
        if (admin && (fragment == null || "schedule-list".equals(fragment))) {
            LocalDate selected = scheduleDate == null ? AttendanceClock.today() : scheduleDate;
            model.addAttribute("scheduleDate", selected);
            model.addAttribute("schedules", policies.schedulesForDate(selected));
        }
        return view;
    }

    @PostMapping("/requests")
    public ResponseEntity<AjaxResult> submit(@RequestParam AttendanceRequestType type,
                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
                         @RequestParam(required = false)
                         @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm") LocalDateTime clockInAt,
                         @RequestParam(required = false)
                         @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm") LocalDateTime clockOutAt,
                         @RequestParam(required = false)
                         @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm") LocalDateTime overtimeEndAt,
                         @RequestParam String reason,
                         HttpSession session) {
        User user = requireUser(session);
        if (!"EMPLOYEE".equals(user.getRole())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        requests.submit(user.getId(), type, startDate, endDate,
                clockInAt, clockOutAt, overtimeEndAt, reason);
        return AjaxResult.ok("申請を受け付けました。審査結果を確認してください。");
    }

    @PostMapping("/requests/{id}/cancel")
    public ResponseEntity<AjaxResult> cancel(@PathVariable Long id, HttpSession session) {
        User user = requireUser(session);
        requests.cancel(user.getId(), id);
        return AjaxResult.ok("申請を取り下げました。");
    }

    @PostMapping("/review/{id}/{decision}")
    public ResponseEntity<AjaxResult> review(@PathVariable Long id, @PathVariable String decision,
                         @RequestParam(required = false) String comment,
                         HttpSession session) {
        User user = requireReviewer(session);
        boolean approved = switch (decision) {
            case "approve" -> true;
            case "reject" -> false;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        };
        requests.review(user.getId(), id, approved, comment);
        return AjaxResult.ok(approved ? "申請を承認しました。" : "申請を差し戻しました。");
    }

    @PostMapping("/policy")
    public ResponseEntity<AjaxResult> policy(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime standardStart,
                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime standardEnd,
                         @RequestParam int breakMinutes,
                         @RequestParam int lateGraceMinutes,
                         @RequestParam int earlyGraceMinutes,
                         @RequestParam int overtimeThresholdMinutes,
                         @RequestParam(required = false) List<Integer> workDay,
                         HttpSession session) {
        User admin = requireAdmin(session);
        int mask = 0;
        if (workDay != null) {
            for (Integer day : workDay) {
                if (day == null || day < 1 || day > 64 || (day & (day - 1)) != 0) {
                    throw new IllegalArgumentException("勤務曜日を確認してください。");
                }
                mask |= day;
            }
        }
        policies.updatePolicy(admin.getId(), standardStart, standardEnd, breakMinutes,
                lateGraceMinutes, earlyGraceMinutes, overtimeThresholdMinutes, mask);
        return AjaxResult.ok("勤務ルールを更新しました。");
    }

    @PostMapping("/schedules")
    public ResponseEntity<AjaxResult> schedule(@RequestParam Long userId,
                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate workDate,
                           @RequestParam DayType dayType,
                           @RequestParam WorkMode workMode,
                           @RequestParam(required = false)
                           @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm") LocalDateTime plannedStartAt,
                           @RequestParam(required = false)
                           @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm") LocalDateTime plannedEndAt,
                           @RequestParam int breakMinutes,
                           HttpSession session) {
        User admin = requireAdmin(session);
        policies.assignSchedule(admin.getId(), userId, workDate, dayType, workMode,
                plannedStartAt, plannedEndAt, breakMinutes);
        return AjaxResult.ok("勤務予定を保存しました。");
    }

    @PostMapping("/holidays")
    public ResponseEntity<AjaxResult> holiday(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                          @RequestParam String name,
                          HttpSession session) {
        User admin = requireAdmin(session);
        policies.saveHoliday(admin.getId(), date, name);
        return AjaxResult.ok("会社休日を登録しました。");
    }

    @PostMapping("/holidays/{date}/delete")
    public ResponseEntity<AjaxResult> deleteHoliday(@PathVariable
                                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                HttpSession session) {
        User admin = requireAdmin(session);
        policies.removeHoliday(admin.getId(), date);
        return AjaxResult.ok("会社休日を無効化しました。記録は保持されています。");
    }

    @PostMapping("/schedules/{id}/delete")
    public ResponseEntity<AjaxResult> deleteSchedule(@PathVariable Long id, HttpSession session) {
        User admin = requireAdmin(session);
        policies.removeSchedule(admin.getId(), id);
        return AjaxResult.ok("個別勤務予定を無効化しました。記録は保持されています。");
    }

    private User requireUser(HttpSession session) {
        return access.currentUser(session)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    private User requireReviewer(HttpSession session) {
        User user = access.currentUser(session)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        if (!access.canApproveAttendance(user)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return user;
    }

    private User requireAdmin(HttpSession session) {
        User user = access.currentUser(session)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        if (!access.canManageAttendancePolicy(user)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return user;
    }

    private int page(int value) { return Math.max(0, Math.min(value, 1000)); }

    // 最終行の取り下げ・審査でページが消えた場合は、残った最終ページを返す。
    private <T> Page<T> boundedPage(int requested, IntFunction<Page<T>> loader) {
        Page<T> result = loader.apply(page(requested));
        int last = Math.max(0, result.getTotalPages() - 1);
        return result.getNumber() > last ? loader.apply(last) : result;
    }
}
