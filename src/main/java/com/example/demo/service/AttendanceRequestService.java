package com.example.demo.service;

import com.example.demo.attendance.AttendanceClock;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.attendance.AttendanceEventType;
import com.example.demo.attendance.AttendanceRequestType;
import com.example.demo.attendance.DayType;
import com.example.demo.attendance.RequestStatus;
import com.example.demo.attendance.ScheduleSnapshot;
import com.example.demo.attendance.WorkMode;
import com.example.demo.entity.AttendanceEvent;
import com.example.demo.entity.AttendanceAuditLog;
import com.example.demo.entity.AttendanceRecord;
import com.example.demo.entity.AttendanceRequest;
import com.example.demo.entity.User;
import com.example.demo.repository.AttendanceEventRepository;
import com.example.demo.repository.AttendanceAuditLogRepository;
import com.example.demo.repository.AttendanceRecordRepository;
import com.example.demo.repository.AttendanceBreakRepository;
import com.example.demo.repository.AttendanceRequestRepository;
import com.example.demo.repository.UserRepository;
import com.example.demo.security.SystemRole;

@Service
public class AttendanceRequestService {

    private final AttendanceRequestRepository requestRepository;
    private final AttendanceRecordRepository recordRepository;
    private final AttendanceBreakRepository breakRepository;
    private final AttendanceEventRepository eventRepository;
    private final AttendancePolicyService policyService;
    private final UserRepository userRepository;
    private final AttendanceAuditLogRepository auditRepository;

    public AttendanceRequestService(AttendanceRequestRepository requestRepository,
                                    AttendanceRecordRepository recordRepository,
                                    AttendanceBreakRepository breakRepository,
                                    AttendanceEventRepository eventRepository,
                                    AttendancePolicyService policyService,
                                    UserRepository userRepository,
                                    AttendanceAuditLogRepository auditRepository) {
        this.requestRepository = requestRepository;
        this.recordRepository = recordRepository;
        this.breakRepository = breakRepository;
        this.eventRepository = eventRepository;
        this.policyService = policyService;
        this.userRepository = userRepository;
        this.auditRepository = auditRepository;
    }

    @Transactional(readOnly = true)
    public Page<AttendanceRequest> ownRequests(Long userId, int page) {
        return requestRepository.findByUser_IdOrderByCreatedAtDesc(userId,
                PageRequest.of(Math.max(0, page), 20));
    }

    @Transactional(readOnly = true)
    public Page<AttendanceRequest> reviewQueue(int page) {
        return requestRepository.findByStatusOrderByCreatedAtAsc(RequestStatus.PENDING,
                PageRequest.of(Math.max(0, page), 20));
    }

    @Transactional(readOnly = true)
    public Page<AttendanceRequest> reviewedBy(Long reviewerId, int page) {
        return requestRepository.findByReviewer_IdOrderByReviewedAtDesc(reviewerId,
                PageRequest.of(Math.max(0, page), 20));
    }

    @Transactional
    @com.example.demo.security.BusinessAudit(operation = "REQUEST_SUBMIT", target = "REQUEST")
    public AttendanceRequest submit(Long userId, AttendanceRequestType type,
                                    LocalDate start, LocalDate end,
                                    LocalDateTime clockIn, LocalDateTime clockOut,
                                    LocalDateTime overtimeEnd, String reason) {
        validate(type, start, end, clockIn, clockOut, overtimeEnd, reason);
        // 同一社員の同時申請を直列化し、重複判定から保存までを同じTXで行う。
        User user = userRepository.findForUpdate(userId).filter(User::isActive)
                .orElseThrow(() -> new IllegalStateException("有効なユーザーではありません。"));
        if (!"EMPLOYEE".equals(user.getRole())) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "新規申請は一般社員のみ利用できます。");
        }
        List<AttendanceRequest> existing = requestRepository
                .findByUser_IdAndRequestTypeAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                        userId, type, RequestStatus.PENDING, end, start);
        if (!existing.isEmpty()) {
            throw new IllegalStateException("同じ種類・期間の審査中の申請があります。");
        }
        AttendanceRequest value = new AttendanceRequest();
        value.setUser(user);
        value.setRequestType(type);
        value.setStartDate(start);
        value.setEndDate(end);
        value.setRequestedClockInAt(clockIn);
        value.setRequestedClockOutAt(clockOut);
        value.setRequestedOvertimeEndAt(overtimeEnd);
        value.setReason(reason.trim());
        AttendanceRequest saved = requestRepository.save(value);
        audit(user, "SUBMIT_REQUEST", saved.getId(), null,
                type + "|" + start + "|" + end);
        return saved;
    }

    @Transactional
    @com.example.demo.security.BusinessAudit(operation = "REQUEST_CANCEL", target = "REQUEST", idArgument = 1)
    public void cancel(Long userId, Long requestId) {
        AttendanceRequest value = requestRepository.findById(requestId)
                .filter(item -> !item.isDeleted())
                .orElseThrow(() -> new IllegalArgumentException("申請が見つかりません。"));
        if (!value.getUser().getId().equals(userId) || value.getStatus() != RequestStatus.PENDING) {
            throw new IllegalStateException("この申請は取り下げできません。");
        }
        value.setStatus(RequestStatus.CANCELLED);
        audit(value.getUser(), "CANCEL_REQUEST", value.getId(), "PENDING", "CANCELLED");
    }

    @Transactional
    @com.example.demo.security.BusinessAudit(operation = "REQUEST_REVIEW", target = "REQUEST", idArgument = 1)
    public void review(Long reviewerId, Long requestId, boolean approved, String comment) {
        // 通常SELECTで古いスナップショットを作る前に、申請と対象社員をロックする。
        AttendanceRequest value = requestRepository.findForUpdate(requestId)
                .filter(item -> !item.isDeleted())
                .orElseThrow(() -> new IllegalArgumentException("申請が見つかりません。"));
        if (approved) {
            userRepository.findForUpdate(value.getUser().getId()).filter(User::isActive)
                    .orElseThrow(() -> new IllegalStateException("無効な従業員の申請は承認できません。差し戻してください。"));
        }
        User reviewer = activeUser(reviewerId);
        SystemRole role = SystemRole.fromCode(reviewer.getRole());
        if (role != SystemRole.ADMIN && role != SystemRole.GENERAL_AFFAIRS) {
            throw new IllegalStateException("申請を審査する権限がありません。");
        }
        if (value.getUser().getId().equals(reviewerId)) {
            throw new IllegalStateException("自分の申請は承認できません。");
        }
        if (value.getStatus() != RequestStatus.PENDING) {
            throw new IllegalStateException("この申請はすでに処理されています。");
        }
        if (comment != null && comment.length() > 1000) {
            throw new IllegalArgumentException("コメントは1000文字以内で入力してください。");
        }
        if (!approved && (comment == null || comment.isBlank())) {
            throw new IllegalArgumentException("差し戻し理由を入力してください。");
        }
        if (approved) {
            applyApproval(value, reviewer);
        }
        value.setStatus(approved ? RequestStatus.APPROVED : RequestStatus.REJECTED);
        value.setReviewer(reviewer);
        value.setReviewComment(comment == null ? null : comment.trim());
        value.setReviewedAt(AttendanceClock.now());
        audit(reviewer, approved ? "APPROVE_REQUEST" : "REJECT_REQUEST",
                value.getId(), "PENDING", value.getStatus().name());
    }

    private void validate(AttendanceRequestType type, LocalDate start, LocalDate end,
                          LocalDateTime clockIn, LocalDateTime clockOut,
                          LocalDateTime overtimeEnd, String reason) {
        if (type == null || start == null || end == null || end.isBefore(start)
                || end.isAfter(start.plusDays(30)) || reason == null || reason.isBlank()
                || reason.length() > 1000) {
            throw new IllegalArgumentException("申請内容・期間・理由を確認してください。期間は最大31日です。");
        }
        LocalDate today = AttendanceClock.today();
        if (start.isBefore(today.minusYears(1)) || end.isAfter(today.plusYears(1))) {
            throw new IllegalArgumentException("申請日は前後1年以内で指定してください。");
        }
        if (type == AttendanceRequestType.CORRECTION) {
            if (!start.equals(end) || (clockIn == null && clockOut == null)
                    || (clockIn != null && !clockIn.toLocalDate().equals(start))
                    || (clockOut != null && (clockOut.isBefore(start.atStartOfDay())
                        || !clockOut.isBefore(start.plusDays(2).atStartOfDay())))
                    || (clockIn != null && clockOut != null && !clockOut.isAfter(clockIn))) {
                throw new IllegalArgumentException("打刻修正の日時を確認してください。勤務日は1日だけ指定します。");
            }
        } else if (type == AttendanceRequestType.OVERTIME) {
            if (!start.equals(end) || overtimeEnd == null
                    || overtimeEnd.isBefore(start.atStartOfDay())
                    || overtimeEnd.isAfter(start.plusDays(2).atStartOfDay())) {
                throw new IllegalArgumentException("残業の予定終了日時を確認してください。");
            }
        }
    }

    private void applyApproval(AttendanceRequest value, User reviewer) {
        Long userId = value.getUser().getId();
        AttendanceRequestType type = value.getRequestType();
        if (type == AttendanceRequestType.CORRECTION) {
            applyCorrection(value, reviewer);
            return;
        }
        if (type == AttendanceRequestType.OVERTIME) {
            ScheduleSnapshot schedule = policyService.resolve(userId, value.getStartDate());
            if (schedule.dayType() != DayType.WORKDAY || schedule.plannedEndAt() == null
                    || !value.getRequestedOvertimeEndAt().isAfter(schedule.plannedEndAt())) {
                throw new IllegalStateException("残業終了日時は勤務予定の終了後に指定してください。");
            }
            return; // 承認状態を実績計算に利用し、予定や原本は変更しない。
        }
        int appliedDays = 0;
        for (LocalDate date = value.getStartDate(); !date.isAfter(value.getEndDate());
             date = date.plusDays(1)) {
            ScheduleSnapshot schedule = policyService.resolve(userId, date);
            if (type == AttendanceRequestType.LEAVE) {
                if (schedule.dayType() != DayType.WORKDAY) continue;
                if (recordRepository.findByUser_IdAndWorkDate(userId, date).isPresent()) {
                    throw new IllegalStateException("打刻済みの日は休暇として承認できません。");
                }
                policyService.assignSchedule(reviewer.getId(), userId, date,
                        DayType.LEAVE, schedule.workMode(), null, null, 0);
                appliedDays++;
            } else if (type == AttendanceRequestType.HOLIDAY_WORK) {
                if (schedule.dayType() == DayType.WORKDAY) {
                    throw new IllegalStateException("すでに勤務日のため休日出勤に変更できません。");
                }
                var policy = policyService.policyForDate(date);
                LocalDateTime begin = date.atTime(policy.standardStart());
                LocalDateTime finish = date.atTime(policy.standardEnd());
                if (!finish.isAfter(begin)) finish = finish.plusDays(1);
                policyService.assignSchedule(reviewer.getId(), userId, date,
                        DayType.WORKDAY, WorkMode.OFFICE, begin, finish, policy.breakMinutes());
                appliedDays++;
            } else if (type == AttendanceRequestType.FIELD || type == AttendanceRequestType.REMOTE) {
                if (schedule.dayType() != DayType.WORKDAY) continue;
                policyService.assignSchedule(reviewer.getId(), userId, date,
                        DayType.WORKDAY,
                        type == AttendanceRequestType.FIELD ? WorkMode.FIELD : WorkMode.REMOTE,
                        schedule.plannedStartAt(), schedule.plannedEndAt(), schedule.breakMinutes());
                appliedDays++;
            }
        }
        if (appliedDays == 0) {
            throw new IllegalStateException("対象期間に変更できる勤務日がありません。");
        }
    }

    private void applyCorrection(AttendanceRequest value, User reviewer) {
        Long userId = value.getUser().getId();
        AttendanceRecord record = recordRepository.findByUser_IdAndWorkDate(userId, value.getStartDate())
                .orElseGet(AttendanceRecord::new);
        LocalDateTime in = value.getRequestedClockInAt() != null
                ? value.getRequestedClockInAt() : record.getClockInAt();
        LocalDateTime out = value.getRequestedClockOutAt() != null
                ? value.getRequestedClockOutAt() : record.getClockOutAt();
        if (in == null || (out != null && !out.isAfter(in))) {
            throw new IllegalStateException("修正後の出勤・退勤時刻が不正です。");
        }
        if (out == null && recordRepository
                .findFirstByUser_IdAndClockOutAtIsNullOrderByClockInAtDesc(userId)
                .filter(open -> !Objects.equals(open.getId(), record.getId())).isPresent()) {
            throw new IllegalStateException("別の未退勤記録があります。先にその記録の退勤時刻を修正してください。");
        }
        if (record.getId() != null) {
            for (var rest : breakRepository.findByAttendanceRecord_IdOrderByStartedAtAsc(record.getId())) {
                if (rest.getStartedAt().isBefore(in)
                        || (out != null && (rest.getEndedAt() == null || rest.getEndedAt().isAfter(out)))) {
                    throw new IllegalStateException("修正後の出退勤時刻が休憩記録と矛盾しています。休憩中は退勤時刻を確定できません。記録を確認して再申請してください。");
                }
            }
        }
        record.restore();
        record.setUser(value.getUser());
        record.setWorkDate(value.getStartDate());
        record.setClockInAt(in);
        record.setClockOutAt(out);
        recordRepository.saveAndFlush(record);
        AttendanceEvent event = new AttendanceEvent();
        event.setAttendanceRecord(record);
        event.setUser(value.getUser());
        event.setActor(reviewer);
        event.setEventType(AttendanceEventType.CORRECTION_APPLIED);
        event.setEventAt(AttendanceClock.now());
        event.setNote("申請#" + value.getId() + " / 出勤=" + in + " / 退勤=" + out);
        eventRepository.save(event);
    }

    private User activeUser(Long id) {
        return userRepository.findById(id)
                .filter(user -> user.isActive())
                .orElseThrow(() -> new IllegalStateException("有効なユーザーではありません。"));
    }

    private void audit(User actor, String action, Long requestId, String before, String after) {
        AttendanceAuditLog log = new AttendanceAuditLog();
        log.setActor(actor);
        log.setActionName(action);
        log.setTargetType("attendance_request");
        log.setTargetId(String.valueOf(requestId));
        log.setOldValue(before);
        log.setNewValue(after);
        auditRepository.save(log);
    }
}
