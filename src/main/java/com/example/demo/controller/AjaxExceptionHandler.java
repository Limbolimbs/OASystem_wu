package com.example.demo.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

/** AJAXの失敗をログインHTMLやエラーHTMLではなくJSONで通知する。 */
@ControllerAdvice
public class AjaxExceptionHandler {
    private final org.springframework.beans.factory.ObjectProvider<com.example.demo.security.AuditService> audit;
    public AjaxExceptionHandler(org.springframework.beans.factory.ObjectProvider<com.example.demo.security.AuditService> audit) { this.audit = audit; }
    @ExceptionHandler(com.example.demo.security.AuditUnavailableException.class)
    public ResponseEntity<AjaxResult> auditUnavailable() {
        org.slf4j.LoggerFactory.getLogger(AjaxExceptionHandler.class).error("業務更新の監査保存障害 correlationId={}", com.example.demo.security.SecurityRequestContext.current().correlationId());
        return AjaxResult.error(HttpStatus.SERVICE_UNAVAILABLE, "監査記録を保存できなかったため、更新を取り消しました。システム管理者へ連絡してください。");
    }
    @ExceptionHandler({ResponseStatusException.class, ServletRequestBindingException.class,
            MethodArgumentTypeMismatchException.class, IllegalArgumentException.class,
            IllegalStateException.class, DataIntegrityViolationException.class,
            ObjectOptimisticLockingFailureException.class, BindException.class})
    public ResponseEntity<AjaxResult> handle(Exception exception, HttpServletRequest request)
            throws Exception {
        if (exception instanceof ResponseStatusException denied && (denied.getStatusCode().value() == 401 || denied.getStatusCode().value() == 403)) {
            var storage = audit.getIfAvailable();
            if (storage != null) try {
                storage.security("ACCESS_REJECTED", com.example.demo.security.SecurityRequestContext.actorId(), "REJECTED");
            } catch (RuntimeException failure) {
                org.slf4j.LoggerFactory.getLogger(AjaxExceptionHandler.class).error("拒否操作の監査保存障害 correlationId={}", com.example.demo.security.SecurityRequestContext.current().correlationId());
            }
        }
        if (!"XMLHttpRequest".equals(request.getHeader("X-Requested-With"))) throw exception;
        if (exception instanceof ResponseStatusException statusException) {
            HttpStatus status = HttpStatus.valueOf(statusException.getStatusCode().value());
            String message = switch (status) {
                case UNAUTHORIZED -> "ログインの有効期限が切れました。もう一度ログインしてください。";
                case FORBIDDEN -> "この操作を行う権限がありません。";
                case NOT_FOUND -> "対象データが見つかりません。";
                default -> statusException.getReason() == null
                        ? "入力内容を確認してください。" : statusException.getReason();
            };
            return AjaxResult.error(status, message);
        }
        if (exception instanceof DataIntegrityViolationException
                || exception instanceof ObjectOptimisticLockingFailureException) {
            return AjaxResult.error(HttpStatus.CONFLICT,
                    "対象データが変更されています。最新の表示を確認してください。");
        }
        if (exception instanceof ServletRequestBindingException
                || exception instanceof MethodArgumentTypeMismatchException
                || exception instanceof BindException) {
            return AjaxResult.error(HttpStatus.BAD_REQUEST, "必須項目・日付・選択内容を確認してください。");
        }
        return AjaxResult.error(exception instanceof IllegalStateException
                ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST, exception.getMessage());
    }
}
