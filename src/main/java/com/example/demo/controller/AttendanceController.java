package com.example.demo.controller;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.example.demo.entity.User;
import com.example.demo.security.AccessControlService;
import com.example.demo.service.AttendanceDayService;
import com.example.demo.service.AttendanceService;
import com.example.demo.repository.UserRepository;

import jakarta.servlet.http.HttpSession;

@Controller
@RequestMapping("/attendance")
public class AttendanceController {

    private final AttendanceService attendanceService;
    private final AttendanceDayService dayService;
    private final AccessControlService accessControl;
    private final UserRepository userRepository;

    public AttendanceController(AttendanceService attendanceService,
                                AttendanceDayService dayService,
                                AccessControlService accessControl,
                                UserRepository userRepository) {
        this.attendanceService = attendanceService;
        this.dayService = dayService;
        this.accessControl = accessControl;
        this.userRepository = userRepository;
    }

    @GetMapping
    public String index(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "0") int page,
            HttpSession session,
            Model model) {

        Optional<User> activeUser = currentUser(session);
        if (activeUser.isEmpty()) {
            return "redirect:/";
        }

        User user = activeUser.get();
        LocalDate selectedDate = date == null ? attendanceService.today() : date;
        boolean admin = accessControl.isAdmin(user);
        boolean reviewer = accessControl.canApproveAttendance(user);
        int safePage = Math.max(0, Math.min(page, 1000));
        var adminRows = reviewer ? dayService.allActiveForDate(selectedDate, safePage) : null;
        var rows = reviewer
                ? adminRows.getContent()
                : date != null
                    ? List.of(dayService.evaluate(user, selectedDate))
                    : IntStream.range(0, 30)
                        .mapToObj(days -> dayService.evaluate(user, selectedDate.minusDays(days)))
                        .toList();
        var current = attendanceService.findOpenRecord(user.getId()).orElse(null);
        if (current == null) current = attendanceService.findTodayRecord(user.getId()).orElse(null);

        model.addAttribute("admin", admin);
        model.addAttribute("reviewer", reviewer);
        model.addAttribute("user", user);
        model.addAttribute("today", attendanceService.today());
        model.addAttribute("selectedDate", selectedDate);
        model.addAttribute("todayRecord", current);
        model.addAttribute("openRecord", attendanceService.findOpenRecord(user.getId()).orElse(null));
        model.addAttribute("openBreak", current == null ? null :
                attendanceService.findOpenBreak(current.getId()).orElse(null));
        model.addAttribute("rows", rows);
        model.addAttribute("adminRows", adminRows);
        model.addAttribute("page", safePage);
        return "attendance";
    }

    @GetMapping("/history")
    public String history(@RequestParam(required = false) Long userId,
                          @RequestParam(required = false)
                          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                          @RequestParam(required = false)
                          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                          HttpSession session, Model model) {
        User actor = accessControl.currentUser(session).orElse(null);
        if (actor == null) return "redirect:/";
        boolean reviewer = accessControl.canViewAllAttendance(actor);
        Long targetId = userId == null ? actor.getId() : userId;
        if (!reviewer && !targetId.equals(actor.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        User target = targetId.equals(actor.getId()) ? actor : userRepository.findById(targetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        LocalDate end = to == null ? attendanceService.today() : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        long span = ChronoUnit.DAYS.between(start, end);
        if (span < 0 || span > 30) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "検索期間は31日以内にしてください。");
        }
        var rows = IntStream.rangeClosed(0, (int) span)
                .mapToObj(offset -> dayService.evaluate(target, end.minusDays(offset)))
                .toList();
        model.addAttribute("user", actor);
        model.addAttribute("target", target);
        model.addAttribute("reviewer", reviewer);
        model.addAttribute("admin", accessControl.isAdmin(actor));
        model.addAttribute("users", reviewer ? userRepository.findAllByOrderByStatusDescIdAsc() : List.of());
        model.addAttribute("from", start);
        model.addAttribute("to", end);
        model.addAttribute("rows", rows);
        return "attendance-history";
    }

    @PostMapping("/clock-in")
    public String clockIn(HttpSession session, RedirectAttributes redirectAttributes) {
        Optional<User> activeUser = currentUser(session);
        if (activeUser.isEmpty()) {
            return "redirect:/";
        }

        try {
            attendanceService.clockIn(activeUser.get().getId());
            redirectAttributes.addFlashAttribute("successMessage", "出勤時刻を記録しました。");
        } catch (IllegalStateException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
        } catch (DataIntegrityViolationException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", "本日はすでに出勤打刻済みです。");
        }

        return "redirect:/attendance";
    }

    @PostMapping("/clock-out")
    public String clockOut(HttpSession session, RedirectAttributes redirectAttributes) {
        Optional<User> activeUser = currentUser(session);
        if (activeUser.isEmpty()) {
            return "redirect:/";
        }

        try {
            attendanceService.clockOut(activeUser.get().getId());
            redirectAttributes.addFlashAttribute("successMessage", "退勤時刻を記録しました。");
        } catch (IllegalStateException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", exception.getMessage());
        } catch (ObjectOptimisticLockingFailureException exception) {
            redirectAttributes.addFlashAttribute("errorMessage", "記録が更新されました。画面を再読み込みしてください。");
        }

        return "redirect:/attendance";
    }

    @PostMapping("/break-start")
    public String breakStart(HttpSession session, RedirectAttributes messages) {
        return breakAction(session, messages, true);
    }

    @PostMapping("/break-end")
    public String breakEnd(HttpSession session, RedirectAttributes messages) {
        return breakAction(session, messages, false);
    }

    private String breakAction(HttpSession session, RedirectAttributes messages, boolean start) {
        Optional<User> active = currentUser(session);
        if (active.isEmpty()) return "redirect:/";
        try {
            if (start) attendanceService.startBreak(active.get().getId());
            else attendanceService.endBreak(active.get().getId());
            messages.addFlashAttribute("successMessage", start ? "休憩を開始しました。" : "休憩を終了しました。");
        } catch (IllegalStateException exception) {
            messages.addFlashAttribute("errorMessage", exception.getMessage());
        }
        return "redirect:/attendance";
    }

    private Optional<User> currentUser(HttpSession session) {
        return accessControl.currentUser(session);
    }
}
