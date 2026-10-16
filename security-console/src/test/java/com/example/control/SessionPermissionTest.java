package com.example.control;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SessionPermissionTest {
    private ConsoleIdentity actor(String role){return new ConsoleIdentity("00000000-0000-4000-8000-000000000001","operator",role,0);}
    private Map<String,Object> row(String realm,String subject,String role){return Map.of("realm",realm,"subject_key",subject,"current_role",role);}
    @Test void ownerCanTerminateEachScopedTarget(){for(var row:List.of(row("OA","1","ADMIN"),row("OA","2","GENERAL_AFFAIRS"),row("CONTROL","other","OWNER")))assertTrue(ConsoleSessions.direct(actor("OWNER"),row));}
    @Test void operatorCanDirectlyEndOnlyEmployeesAndOwnManagementSessions(){assertTrue(ConsoleSessions.direct(actor("OPERATOR"),row("OA","1","EMPLOYEE")));assertTrue(ConsoleSessions.direct(actor("OPERATOR"),row("CONTROL",actor("OPERATOR").operatorId(),"OPERATOR")));for(String role:List.of("ADMIN","GENERAL_AFFAIRS","UNKNOWN"))assertFalse(ConsoleSessions.direct(actor("OPERATOR"),row("OA","1",role)));assertFalse(ConsoleSessions.direct(actor("OPERATOR"),row("CONTROL","other","OBSERVER")));}
    @Test void observerCanOnlyEndOwnManagementSessions(){assertTrue(ConsoleSessions.direct(actor("OBSERVER"),row("CONTROL",actor("OBSERVER").operatorId(),"OBSERVER")));assertFalse(ConsoleSessions.direct(actor("OBSERVER"),row("CONTROL","other","OWNER")));assertFalse(ConsoleSessions.direct(actor("OBSERVER"),row("OA","1","EMPLOYEE")));}
}
