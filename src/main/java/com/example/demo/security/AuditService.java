package com.example.demo.security;

import java.time.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class AuditService {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AccessEvidenceWriter evidence;
    public AuditService(JdbcTemplate jdbc, Clock clock,AccessEvidenceWriter evidence) { this.jdbc = jdbc; this.clock = clock;this.evidence=evidence; }

    /** 呼出元の更新トランザクションへ参加する。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String operation, Long actorId, String targetType, String targetId,
            String result, String changes, String subjectKey) {
        var context = SecurityRequestContext.current();
        jdbc.update("INSERT INTO security_audit_log (occurred_at,actor_id,operation_code,target_type,target_id,result,"
                + "correlation_id,source_key,subject_key,changes_json) VALUES (?,?,?,?,?,?,?,?,?,?)",
            LocalDateTime.ofInstant(clock.instant(), ZoneId.of("Asia/Tokyo")),
            actorId, operation, targetType, targetId, result, context.correlationId(), context.sourceKey(), subjectKey, changes);
        evidence.record(context.correlationId(),context.sourceKey(),context.details());
    }

    /** 認証拒否・期限切れは業務更新とは独立して記録する。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void security(String operation, Long actorId, String result) {
        record(operation, actorId, "SECURITY", actorId == null ? null : actorId.toString(), result, null, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void rejected(String operation, Long actorId, String targetType, String targetId) {
        record(operation, actorId, targetType, targetId, "REJECTED", null, null);
    }
}
