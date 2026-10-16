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
import org.springframework.http.ResponseEntity;

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
            @RequestParam(required = false) String fragment,
            HttpSession session,
            Model model) {

        Optional<User> activeUser = currentUser(session);
        if (activeUser.isEmpty()) {
            if (fragment != null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
            return "redirect:/";
        }

        User user = activeUser.get();
        String view = AjaxResult.view("attendance", fragment, "attendance-overview", "attendance-records");
        LocalDate selectedDate = date == null ? attendanceService.today() : date;
        boolean admin = accessControl.isAdmin(user);
        boolean reviewer = accessControl.canApproveAttendance(user);
        int safePage = Math.max(0, Math.min(page, 1000));
        model.addAttribute("admin", admin);
        model.addAttribute("salesAccess", accessControl.canManageSales(user));
        model.addAttribute("reviewer", reviewer);
        model.addAttribute("user", user);
        model.addAttribute("today", attendanceService.today());
        model.addAttribute("selectedDate", selectedDate);
        model.addAttribute("filtered", date != null);
        if (fragment == null || "attendance-overview".equals(fragment)) {
            var open = attendanceService.findOpenRecord(user.getId()).orElse(null);
            var current = open != null ? open : attendanceService.findTodayRecord(user.getId()).orElse(null);
            model.addAttribute("todayRecord", current);
            model.addAttribute("openRecord", open);
            model.addAttribute("openBreak", current == null ? null :
                    attendanceService.findOpenBreak(current.getId()).orElse(null));
        }
        if (fragment == null || "attendance-records".equals(fragment)) {
            var adminRows = reviewer ? dayService.allActiveForDate(selectedDate, safePage) : null;
            if (adminRows != null && safePage >= adminRows.getTotalPages() && safePage > 0) {
                safePage = Math.max(0, adminRows.getTotalPages() - 1);
                adminRows = dayService.allActiveForDate(selectedDate, safePage);
            }
            var rows = reviewer
                    ? adminRows.getContent()
                    : date != null
                        ? List.of(dayService.evaluate(user, selectedDate))
                        : IntStream.range(0, 30)
                            .mapToObj(days -> dayService.evaluate(user, selectedDate.minusDays(days)))
                            .toList();
            model.addAttribute("rows", rows);
            model.addAttribute("adminRows", adminRows);
        }
        model.addAttribute("page", safePage);
        return view;
    }

    @GetMapping("/history")
    public String history(@RequestParam(required = false) Long userId,
                          @RequestParam(required = false) String fragment,
                          @RequestParam(required = false)
                          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                          @RequestParam(required = false)
                          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                          HttpSession session, Model model) {
        User actor = accessControl.currentUser(session).orElse(null);
        if (actor == null) {
            if (fragment != null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
            return "redirect:/";
        }
        String view = AjaxResult.view("attendance-history", fragment, "history-results", "history-title");
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
        model.addAttribute("user", actor);
        model.addAttribute("target", target);
        model.addAttribute("reviewer", reviewer);
        model.addAttribute("admin", accessControl.isAdmin(actor));
        model.addAttribute("salesAccess", accessControl.canManageSales(actor));
        if (fragment == null) {
            model.addAttribute("users", reviewer ? userRepository.findAllByOrderByStatusDescIdAsc() : List.of());
        }
        model.addAttribute("from", start);
        model.addAttribute("to", end);
        if (fragment == null || "history-results".equals(fragment)) {
            model.addAttribute("rows", IntStream.rangeClosed(0, (int) span)
                    .mapToObj(offset -> dayService.evaluate(target, end.minusDays(offset))).toList());
        }
        return view;
    }

    @PostMapping("/clock-in")
    public ResponseEntity<AjaxResult> clockIn(HttpSession session) {
        User user = requireUser(session);
        try {
            attendanceService.clockIn(user.getId());
        } catch (DataIntegrityViolationException exception) {
            return AjaxResult.error(HttpStatus.CONFLICT, "本日はすでに出勤打刻済みです。");
        }
        return AjaxResult.ok("出勤時刻を記録しました。");
    }

    @PostMapping("/clock-out")
    public ResponseEntity<AjaxResult> clockOut(HttpSession session) {
        User user = requireUser(session);
        try {
            attendanceService.clockOut(user.getId());
        } catch (ObjectOptimisticLockingFailureException exception) {
            return AjaxResult.error(HttpStatus.CONFLICT, "記録が更新されました。最新の表示を確認してください。");
        }
        return AjaxResult.ok("退勤時刻を記録しました。");
    }

    @PostMapping("/break-start")
    public ResponseEntity<AjaxResult> breakStart(HttpSession session) {
        return breakAction(session, true);
    }

    @PostMapping("/break-end")
    public ResponseEntity<AjaxResult> breakEnd(HttpSession session) {
        return breakAction(session, false);
    }

    private ResponseEntity<AjaxResult> breakAction(HttpSession session, boolean start) {
        User user = requireUser(session);
        if (start) attendanceService.startBreak(user.getId());
        else attendanceService.endBreak(user.getId());
        return AjaxResult.ok(start ? "休憩を開始しました。" : "休憩を終了しました。");
    }

    private User requireUser(HttpSession session) {
        return currentUser(session)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    private Optional<User> currentUser(HttpSession session) {
        return accessControl.currentUser(session);
    }
}
