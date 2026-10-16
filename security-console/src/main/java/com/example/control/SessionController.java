package com.example.control;

import java.util.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Controller
public class SessionController {
    private final ConsoleSessions sessions;
    public SessionController(ConsoleSessions sessions){this.sessions=sessions;}
    @GetMapping("/sessions") String screen(Authentication auth,Model model){model.addAttribute("operator",auth.getName());model.addAttribute("role",((ConsoleIdentity)auth.getPrincipal()).role());return "sessions";}
    @GetMapping("/api/sessions") @ResponseBody Map<String,Object> list(@RequestParam(defaultValue="ALL")String realm,@RequestParam(defaultValue="ACTIVE")String state,@RequestParam(defaultValue="")String keyword,@RequestParam(defaultValue="")String ip,@RequestParam(defaultValue="0")int page){return sessions.list(realm,state,keyword.trim(),ip.trim(),page);}
    @PostMapping("/api/sessions/{ref}/{action}") @ResponseBody Map<String,Object> terminate(@PathVariable String ref,@PathVariable String action,@RequestParam long version,@RequestParam String reason,@RequestParam String otp){
        if(!Set.of("revoke","revoke-all","request","request-all").contains(action))throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return action.startsWith("request")?sessions.request(ref,version,action.equals("request-all"),reason,otp):sessions.terminate(ref,version,action.equals("revoke-all"),reason,otp);
    }
}
