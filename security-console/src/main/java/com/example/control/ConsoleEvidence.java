package com.example.control;

import com.example.audit.AccessEnvelope;
import java.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class ConsoleEvidence {
    private final JdbcTemplate jdbc;private final PrivateKey privateKey;private final PublicKey publicKey;private final SourceGeography geography;
    public ConsoleEvidence(JdbcTemplate jdbc,SourceGeography geography,
            @Value("${control.evidence-private-key:}")String privateValue,
            @Value("${control.evidence-public-key:file:D:/oa-maintenance/control-public/evidence-public.pem}")org.springframework.core.io.Resource publicResource){
        this.jdbc=jdbc;this.geography=geography;
        try{privateKey=privateValue.isBlank()?null:AccessEnvelope.privateKey(privateValue);
            if(privateKey==null){publicKey=null;}else{try(var input=publicResource.getInputStream()){publicKey=AccessEnvelope.publicKey(new String(input.readNBytes(16384),java.nio.charset.StandardCharsets.US_ASCII));}if(!AccessEnvelope.keyId(privateKey).equals(AccessEnvelope.keyId(publicKey)))throw new GeneralSecurityException();}
        }catch(Exception failure){throw new IllegalStateException("アクセス証跡の鍵を確認してください。");}
    }
    public void record(ConsoleRequestContext.Context context){
        if(privateKey==null||context.details()==null||!context.details().containsKey("ip"))return;
        try{
            Timestamp now=Timestamp.valueOf(LocalDateTime.now(ZoneId.of("Asia/Tokyo")));
            String value=AccessEnvelope.seal(publicKey,context.id(),context.details());
            try{jdbc.update("INSERT INTO security_access_evidence(correlation_id,source_key,occurred_at,producer,key_id,encrypted_detail) VALUES(?,?,?,'CONTROL',?,?)",context.id(),context.source(),now,AccessEnvelope.keyId(publicKey),value);}catch(org.springframework.dao.DuplicateKeyException existing){/* 同一要求の最初の証跡を保持 */}
        }catch(java.security.GeneralSecurityException|java.io.IOException failure){throw new IllegalStateException("管理証跡を暗号化できません。");}
    }
    private Map<String,Object> decode(Map<String,Object> row){
        var result=new LinkedHashMap<String,Object>();Object encrypted=row.get("encrypted_detail");
        if(encrypted==null){result.put("status","未取得（旧記録）");return result;}
        if(privateKey==null){result.put("status","復号鍵未設定");return result;}
        try{
            Map<String,String> clear=AccessEnvelope.open(privateKey,Objects.toString(row.get("correlation_id")),encrypted.toString());
            result.putAll(clear);result.put("status","取得済み");result.put("observedAt",row.get("occurred_at"));result.put("producer",row.get("producer"));
        }catch(Exception failure){result.put("status","復号不可（別の鍵・破損）");}
        return result;
    }
    public void enrich(List<Map<String,Object>> rows){
        for(var row:rows){row.put("access",decode(row));row.remove("encrypted_detail");row.remove("key_id");row.remove("producer");}
    }
    public Map<String,Object> detail(String id){
        if(!id.matches("[a-fA-F0-9-]{36}"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"相関IDが不正です。");
        var rows=jdbc.queryForList("SELECT correlation_id,source_key,occurred_at,producer,key_id,encrypted_detail FROM security_access_evidence WHERE correlation_id=?",id);
        var result=rows.isEmpty()?new LinkedHashMap<String,Object>(Map.of("status","未取得（旧記録）")):new LinkedHashMap<>(decode(rows.getFirst()));
        result.put("correlationId",id);result.put("geography",geography.lookup(Objects.toString(result.get("ip"),null)));
        result.put("events",jdbc.queryForList("SELECT occurred_at,operation_code,target_type,target_id,result FROM security_audit_log WHERE correlation_id=? ORDER BY id LIMIT 50",id));
        result.put("actions",jdbc.queryForList("SELECT created_at,operation_code,target_type,result,reason FROM security_console_action WHERE correlation_id=? ORDER BY id LIMIT 50",id));
        return result;
    }
}
