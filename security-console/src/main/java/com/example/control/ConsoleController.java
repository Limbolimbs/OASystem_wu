package com.example.control;

import java.util.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.core.Authentication;

@Controller
public class ConsoleController {
    private final ConsoleStore store;
    private final ConsoleEvidence evidence;
    public ConsoleController(ConsoleStore store,ConsoleEvidence evidence) { this.store=store;this.evidence=evidence; }
    @GetMapping("/") String login() { return "access"; }
    @GetMapping("/console") String console(Authentication auth,Model model) { model.addAttribute("operator",auth.getName());model.addAttribute("role",((ConsoleIdentity)auth.getPrincipal()).role());return "console"; }
    @GetMapping("/api/reviews") @ResponseBody List<Map<String,Object>> reviews(){return store.reviews();}
    @PostMapping("/api/reviews") @ResponseBody Map<String,Object> requestReview(@RequestParam String operation,@RequestParam String target,@RequestParam(required=false)Integer minutes,@RequestParam String reason,@RequestParam String otp){store.requestReview(operation,target,minutes,reason,otp);return Map.of("success",true,"message","最高管理者へ処理を申請しました。");}
    @PostMapping("/api/reviews/{id}/{action}") @ResponseBody Map<String,Object> review(@PathVariable long id,@PathVariable String action,@RequestParam String reason,@RequestParam String otp){if(!Set.of("approve","reject").contains(action))throw new ResponseStatusException(HttpStatus.NOT_FOUND);store.review(id,action.equals("approve"),reason,otp);return Map.of("success",true,"message","審査結果を保存しました。");}
    @GetMapping("/api/overview") @ResponseBody Map<String,Object> overview() {return store.overview();}
    @GetMapping("/api/accounts") @ResponseBody List<Map<String,Object>> accounts(@RequestParam(defaultValue="")String keyword){return store.accounts(keyword.trim());}
    @GetMapping("/api/sources") @ResponseBody List<Map<String,Object>> sources(@RequestParam(defaultValue="0")int page){return store.sources(page(page));}
    @GetMapping("/api/events") @ResponseBody List<Map<String,Object>> events(@RequestParam(defaultValue="ALL")String kind,@RequestParam(defaultValue="0")int page){return store.events(kind,page(page));}
    @GetMapping("/api/actions") @ResponseBody List<Map<String,Object>> actions(@RequestParam(defaultValue="0")int page){return store.actions(page(page));}
    @GetMapping("/api/evidence/{id}") @ResponseBody Map<String,Object> evidence(@PathVariable String id){return evidence.detail(id);}
    @PostMapping("/api/activity") @ResponseBody Map<String,Object> activity(){return Map.of("success",true);}
    @PostMapping("/api/accounts/{id}/{action}") @ResponseBody Map<String,Object> account(@PathVariable long id,@PathVariable String action,@RequestParam String reason,@RequestParam String otp){
        if(!Set.of("protect","release").contains(action))throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        store.account(id,action.equals("protect"),reason,otp);return Map.of("success",true,"message",action.equals("protect")?"認証を保護し、旧セッションを失効させました。役割・社員情報は変更していません。":"認証制限を解除しました。旧セッションは復活しません。");
    }
    @PostMapping("/api/sources/{key}/{action}") @ResponseBody Map<String,Object> source(@PathVariable String key,@PathVariable String action,@RequestParam(required=false)Integer minutes,@RequestParam String reason,@RequestParam String otp){
        if(!Set.of("restrict","release").contains(action))throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if(action.equals("restrict")&&minutes==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"制限期間を選択してください。");
        store.source(key,action.equals("release")?null:minutes,reason,otp);return Map.of("success",true,"message","接続元の制限設定を更新しました。");
    }
    private int page(int value){if(value<0||value>1000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"ページを確認してください。");return value;}
}
