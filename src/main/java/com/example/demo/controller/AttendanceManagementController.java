package com.example.demo.controller;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

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
                        @RequestParam(required = false)
                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate scheduleDate,
                        HttpSession session, Model model) {
        User user = access.currentUser(session).orElse(null);
        if (user == null) return "redirect:/";
        boolean admin = access.isAdmin(user);
        boolean reviewer = access.canApproveAttendance(user);
        model.addAttribute("user", user);
        model.addAttribute("admin", admin);
        model.addAttribute("reviewer", reviewer);
        model.addAttribute("requestTypes", AttendanceRequestType.values());
        model.addAttribute("ownRequests", requests.ownRequests(user.getId(), page(ownPage)));
        model.addAttribute("ownPage", page(ownPage));
        if (reviewer) {
            model.addAttribute("reviewRequests", requests.reviewQueue(page(reviewPage)));
            model.addAttribute("reviewPage", page(reviewPage));
            model.addAttribute("reviewedRequests", requests.reviewedBy(user.getId(), page(reviewedPage)));
            model.addAttribute("reviewedPage", page(reviewedPage));
        }
        if (admin) {
            model.addAttribute("policy", policies.currentPolicy());
            int mask = policies.currentPolicy().getWorkDaysMask();
            model.addAttribute("workdayFlags", Map.of(
                    "mon", (mask & 1) != 0, "tue", (mask & 2) != 0,
                    "wed", (mask & 4) != 0, "thu", (mask & 8) != 0,
                    "fri", (mask & 16) != 0, "sat", (mask & 32) != 0,
                    "sun", (mask & 64) != 0));
            model.addAttribute("holidays", policies.holidays());
            model.addAttribute("employees", users.findByStatusOrderByIdAsc(1));
            model.addAttribute("dayTypes", DayType.values());
            model.addAttribute("workModes", WorkMode.values());
            LocalDate selected = scheduleDate == null ? LocalDate.now(java.time.ZoneId.of("Asia/Tokyo")) : scheduleDate;
            model.addAttribute("scheduleDate", selected);
            model.addAttribute("schedules", policies.schedulesForDate(selected));
        }
        return "attendance-manage";
    }

    @PostMapping("/requests")
    public String submit(@RequestParam AttendanceRequestType type,
                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
                         @RequestParam(required = false)
                         @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm") LocalDateTime clockInAt,
                         @RequestParam(required = false)
                         @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm") LocalDateTime clockOutAt,
                         @RequestParam(required = false)
                         @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm") LocalDateTime overtimeEndAt,
                         @RequestParam String reason,
                         HttpSession session, RedirectAttributes flash) {
        User user = access.currentUser(session).orElse(null);
        if (user == null) return "redirect:/";
        try {
            requests.submit(user.getId(), type, startDate, endDate,
                    clockInAt, clockOutAt, overtimeEndAt, reason);
            flash.addFlashAttribute("successMessage", "申請を受け付けました。審査結果を確認してください。");
        } catch (IllegalArgumentException | IllegalStateException ex) {
            flash.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/attendance/manage";
    }

    @PostMapping("/requests/{id}/cancel")
    public String cancel(@PathVariable Long id, HttpSession session, RedirectAttributes flash) {
        User user = access.currentUser(session).orElse(null);
        if (user == null) return "redirect:/";
        try {
            requests.cancel(user.getId(), id);
            flash.addFlashAttribute("successMessage", "申請を取り下げました。");
        } catch (IllegalArgumentException | IllegalStateException ex) {
            flash.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/attendance/manage";
    }

    @PostMapping("/review/{id}/{decision}")
    public String review(@PathVariable Long id, @PathVariable String decision,
                         @RequestParam(required = false) String comment,
                         HttpSession session, RedirectAttributes flash) {
        User user = requireReviewer(session);
        boolean approved = switch (decision) {
            case "approve" -> true;
            case "reject" -> false;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        };
        try {
            requests.review(user.getId(), id, approved, comment);
            flash.addFlashAttribute("successMessage", approved ? "申請を承認しました。" : "申請を差し戻しました。");
        } catch (IllegalArgumentException | IllegalStateException ex) {
            flash.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/attendance/manage";
    }

    @PostMapping("/policy")
    public String policy(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime standardStart,
                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime standardEnd,
                         @RequestParam int breakMinutes,
                         @RequestParam int lateGraceMinutes,
                         @RequestParam int earlyGraceMinutes,
                         @RequestParam int overtimeThresholdMinutes,
                         @RequestParam(required = false) List<Integer> workDay,
                         HttpSession session, RedirectAttributes flash) {
        User admin = requireAdmin(session);
        try {
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
            flash.addFlashAttribute("successMessage", "勤務ルールを更新しました。");
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/attendance/manage#rules";
    }

    @PostMapping("/schedules")
    public String schedule(@RequestParam Long userId,
                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate workDate,
                           @RequestParam DayType dayType,
                           @RequestParam WorkMode workMode,
                           @RequestParam(required = false)
                           @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm") LocalDateTime plannedStartAt,
                           @RequestParam(required = false)
                           @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm") LocalDateTime plannedEndAt,
                           @RequestParam int breakMinutes,
                           HttpSession session, RedirectAttributes flash) {
        User admin = requireAdmin(session);
        try {
            policies.assignSchedule(admin.getId(), userId, workDate, dayType, workMode,
                    plannedStartAt, plannedEndAt, breakMinutes);
            flash.addFlashAttribute("successMessage", "勤務予定を保存しました。");
        } catch (IllegalArgumentException | IllegalStateException ex) {
            flash.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/attendance/manage#schedules";
    }

    @PostMapping("/holidays")
    public String holiday(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                          @RequestParam String name,
                          HttpSession session, RedirectAttributes flash) {
        User admin = requireAdmin(session);
        try {
            policies.saveHoliday(admin.getId(), date, name);
            flash.addFlashAttribute("successMessage", "会社休日を登録しました。");
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/attendance/manage#holidays";
    }

    @PostMapping("/holidays/{date}/delete")
    public String deleteHoliday(@PathVariable
                                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                HttpSession session, RedirectAttributes flash) {
        User admin = requireAdmin(session);
        try {
            policies.removeHoliday(admin.getId(), date);
            flash.addFlashAttribute("successMessage", "会社休日を削除しました。");
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/attendance/manage#holidays";
    }

    @PostMapping("/schedules/{id}/delete")
    public String deleteSchedule(@PathVariable Long id, HttpSession session,
                                 RedirectAttributes flash) {
        User admin = requireAdmin(session);
        try {
            policies.removeSchedule(admin.getId(), id);
            flash.addFlashAttribute("successMessage", "個別勤務予定を削除しました。");
        } catch (IllegalArgumentException ex) {
            flash.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/attendance/manage#schedules";
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
}
