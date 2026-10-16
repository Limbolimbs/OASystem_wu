package com.example.control;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
@RestControllerAdvice
public class ConsoleErrors {
    @org.springframework.beans.factory.annotation.Autowired(required=false) private ConsoleMembers members;
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class) ResponseEntity<?> missing(Exception failure){return ResponseEntity.status(404).body(Map.of("success",false,"message","対象が見つかりません。"));}
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class) ResponseEntity<?> method(Exception failure){return ResponseEntity.status(405).body(Map.of("success",false,"message","この操作方法は利用できません。"));}
    @ExceptionHandler(ResponseStatusException.class) ResponseEntity<?> input(ResponseStatusException failure){if(failure.getStatusCode().value()==403 && members!=null){try{members.denied();}catch(RuntimeException auditFailure){return ResponseEntity.status(503).body(Map.of("success",false,"message","拒否記録を保存できません。操作は実行していません。"));}}return ResponseEntity.status(failure.getStatusCode()).body(Map.of("success",false,"message",failure.getReason()==null?"操作条件を確認してください。":failure.getReason()));}
    @ExceptionHandler({org.springframework.web.bind.ServletRequestBindingException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class}) ResponseEntity<?> binding(Exception failure){return ResponseEntity.badRequest().body(Map.of("success",false,"message","入力内容を確認してください。"));}
    @ExceptionHandler(Exception.class) ResponseEntity<?> storage(Exception failure){
        org.slf4j.LoggerFactory.getLogger(ConsoleErrors.class).error("管理処理障害 correlationId={}",ConsoleRequestContext.current().id());
        return ResponseEntity.status(503).body(Map.of("success",false,"message","処理結果を確認できません。再送信前に一覧を確認してください。"));
    }
}
