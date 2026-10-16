package com.example.control;
import java.util.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class MemberController {
    private final ConsoleMembers members;
    public MemberController(ConsoleMembers members){this.members=members;}
    @GetMapping("/members") String page(Model model){var actor=members.require("OWNER",false);model.addAttribute("operator",actor.loginId());return "members";}
    @GetMapping("/personal") String personal(Model model){var actor=members.require("PERSONAL",false);model.addAttribute("operator",actor.loginId());model.addAttribute("owner",actor.role().equals("OWNER"));return "personal";}
    @GetMapping("/api/members") @ResponseBody List<Map<String,Object>> list(){return members.list();}
    @PostMapping("/api/members") @ResponseBody Map<String,Object> create(@RequestParam String login,@RequestParam String display,@RequestParam String role,@RequestParam String reason,@RequestParam String otp,HttpServletResponse response){noStore(response);var invite=members.create(login,display,role,reason,otp);return Map.of("success",true,"id",invite.id(),"token",invite.token(),"message","担当者を登録しました。招待は24時間・一回限りです。");}
    @PostMapping("/api/members/{id}/{action}") @ResponseBody Map<String,Object> change(@PathVariable String id,@PathVariable String action,@RequestParam(required=false)String display,@RequestParam(required=false)String role,@RequestParam String reason,@RequestParam String otp,HttpServletResponse response){noStore(response);var result=members.change(id,action,display,role,reason,otp);return Map.of("success",true,"token",result.token(),"message","担当者の設定を更新しました。既存セッションは失効します。");}
    @PostMapping("/api/personal/password") @ResponseBody Map<String,Object> password(@RequestParam String current,@RequestParam String password,@RequestParam String confirm,@RequestParam String otp,HttpServletRequest request){if(!Objects.equals(password,confirm))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"新しいパスワードが一致しません。");members.password(current,password,otp);var session=request.getSession(false);if(session!=null)session.invalidate();org.springframework.security.core.context.SecurityContextHolder.clearContext();return Map.of("success",true,"message","パスワードを変更しました。再ログインしてください。");}
    @GetMapping("/enrollment") String enrollment(HttpServletResponse response){noStore(response);return "enrollment";}
    @PostMapping("/enrollment/start") @ResponseBody Map<String,Object> start(@RequestParam String login,@RequestParam String token,@RequestParam String password,@RequestParam String confirm,HttpServletRequest request,HttpServletResponse response){noStore(response);if(!Objects.equals(password,confirm))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"パスワードが一致しません。");if(request.getUserPrincipal()!=null)throw new ResponseStatusException(HttpStatus.CONFLICT,"ログアウト後、本人のブラウザで開通してください。");var setup=members.start(login,token,password);if(setup==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"招待情報又は有効期限を確認してください。");request.getSession().setAttribute("memberSetup",setup);request.changeSessionId();return members.setup(setup);}
    @GetMapping("/enrollment/status") @ResponseBody Map<String,Object> status(HttpSession session,HttpServletResponse response){noStore(response);return members.setup((ConsoleMembers.Setup)session.getAttribute("memberSetup"));}
    @PostMapping("/enrollment/confirm") @ResponseBody Map<String,Object> confirm(@RequestParam String otp,HttpSession session,HttpServletResponse response){noStore(response);if(!members.confirm((ConsoleMembers.Setup)session.getAttribute("memberSetup"),otp))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"認証コードを確認してください。失敗5回で登録期限内の再試行を制限します。");return Map.of("success",true,"message","認証器を登録しました。最高管理者へ本人確認と有効化を依頼してください。");}
    static void noStore(HttpServletResponse response){response.setHeader("Cache-Control","no-store");response.setHeader("Pragma","no-cache");}
}
