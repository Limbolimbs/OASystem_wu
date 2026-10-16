package com.example.control;
import java.io.Serializable;
import java.security.Principal;
public record ConsoleIdentity(String operatorId, String loginId, String role, long version) implements Principal, Serializable {
    public ConsoleIdentity(String operatorId,String loginId){this(operatorId,loginId,"OWNER",0);}
    public String getName() { return loginId; }
}
