package com.example.demo.security;

import java.util.*;
import com.example.demo.entity.*;
import com.example.demo.repository.UserRepository;
import com.example.demo.repository.DepartmentRepository;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 重要更新と監査を同じTXにする。入力DTOや本文を丸ごと記録しない。 */
@Aspect
@Component
@Order(100)
public class BusinessAuditAspect {
    private final TransactionTemplate transaction;
    private final AuditService audit;
    private final AccountSecurityState state;
    private final UserRepository users;
    private final DepartmentRepository departments;
    @org.springframework.beans.factory.annotation.Autowired
    private AccountProtection protection;
    @org.springframework.beans.factory.annotation.Autowired
    private com.example.security.SessionRegistry registry;
    public BusinessAuditAspect(PlatformTransactionManager manager, AuditService audit, AccountSecurityState state, UserRepository users, DepartmentRepository departments) {
        transaction = new TransactionTemplate(manager); this.audit = audit; this.state = state; this.users = users;
        this.departments = departments;
    }
    private Map<String, Object> resourceState(String type, String identifier) {
        if (type.equals("USER")) return userState(identifier);
        if (type.equals("DEPARTMENT") && identifier != null) {
            return departments.findById(Long.valueOf(identifier)).map(department ->
                Map.<String, Object>of("status", department.getStatus(), "salesEnabled", department.isSalesEnabled())).orElse(Map.of());
        }
        return Map.of();
    }
    private static class BusinessFailure extends RuntimeException {
        private final Throwable original;
        BusinessFailure(Throwable original) { this.original = original; }
    }
    private static String id(Object value) {
        if (value instanceof Long number) return number.toString();
        if (value instanceof java.time.LocalDate date) return date.toString();
        if (value != null && value.getClass().getPackageName().equals("com.example.demo.entity")) {
            try { Object identifier = value.getClass().getMethod("getId").invoke(value); return identifier instanceof Long number ? number.toString() : null; }
            catch (ReflectiveOperationException exception) { return null; }
        }
        return null;
    }
    private Map<String, Object> userState(String identifier) {
        if (identifier == null) return Map.of();
        return users.findById(Long.valueOf(identifier)).map(user -> {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("role", user.getRole()); fields.put("status", user.getStatus()); fields.put("department", user.getDepartment());
            return fields;
        }).orElseGet(LinkedHashMap::new);
    }
    @Around("@annotation(specification)")
    public Object record(ProceedingJoinPoint call, BusinessAudit specification) throws Throwable {
        Long actor = SecurityRequestContext.actorId();
        String inputId = specification.idArgument() < 0 ? null : id(call.getArgs()[specification.idArgument()]);
        try {
            return transaction.execute(status -> {
                if (protection != null) protection.requireCurrentMutation();
                if (registry != null && actor != null) registry.requireCurrent();
                boolean administration = specification.target().equals("USER") || specification.target().equals("DEPARTMENT");
                if (administration) state.lockManagement();
                Map<String, Object> before = resourceState(specification.target(), inputId);
                Object result;
                try { result = call.proceed(); } catch (Throwable exception) { throw new BusinessFailure(exception); }
                String target = id(result) == null ? inputId : id(result);
                String changes = null;
                if (administration) {
                    Map<String, Object> after = resourceState(specification.target(), target);
                    changes = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(Map.of("before", before, "after", after));
                }
                if (specification.operation().equals("REQUEST_REVIEW")) {
                    changes = "{\"approved\":" + Boolean.TRUE.equals(call.getArgs()[2]) + "}";
                }
                try { audit.record(specification.operation(), actor, specification.target(), target,
                        Boolean.FALSE.equals(result) ? "REJECTED" : "SUCCESS", changes, null); }
                catch (RuntimeException exception) { throw new AuditUnavailableException(); }
                return result;
            });
        } catch (BusinessFailure failure) {
            try { audit.rejected(specification.operation() + "_REFUSED", actor, specification.target(), inputId); }
            catch (RuntimeException exception) { throw new AuditUnavailableException(); }
            throw failure.original;
        }
    }
}
