package com.example.control;
import java.util.UUID;
public final class ConsoleRequestContext {
    public record Context(String id,String source,java.util.Map<String,String> details) {public Context(String id,String source){this(id,source,null);}}
    private static final ThreadLocal<Context> CURRENT=new ThreadLocal<>();
    private ConsoleRequestContext() {}
    static void begin(String source) { CURRENT.set(new Context(UUID.randomUUID().toString(),OperatorCrypto.hash(source==null?"unknown":source))); }
    static void begin(jakarta.servlet.http.HttpServletRequest request){CURRENT.set(new Context(UUID.randomUUID().toString(),OperatorCrypto.hash(request.getRemoteAddr()==null?"unknown":request.getRemoteAddr()),com.example.audit.AccessFields.capture(request)));}
    static void clear() { CURRENT.remove(); }
    public static Context current() { var result=CURRENT.get(); return result==null?new Context(UUID.randomUUID().toString(),OperatorCrypto.hash("local")):result; }
}
