package com.example.demo.security;

import com.example.audit.AccessEnvelope;
import java.security.PublicKey;
import java.time.*;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AccessEvidenceWriter {
    private final JdbcTemplate jdbc;private final Clock clock;private final PublicKey recipient;private final String keyId;
    public AccessEvidenceWriter(JdbcTemplate jdbc,Clock clock,ResourceLoader loader,
            @Value("${oa.security.evidence-public-key:file:D:/oa-maintenance/control-public/evidence-public.pem}")String location){
        this.jdbc=jdbc;this.clock=clock;
        try(var input=loader.getResource(location).getInputStream()){recipient=AccessEnvelope.publicKey(new String(input.readNBytes(16384),java.nio.charset.StandardCharsets.US_ASCII));keyId=AccessEnvelope.keyId(recipient);}
        catch(Exception failure){throw new IllegalStateException("アクセス補助情報の公開鍵を設定してください。");}
    }
    public void record(String correlation,String source,Map<String,String> fields){
        if(fields==null||!fields.containsKey("ip")||source==null)return;
        String encrypted;
        try{encrypted=AccessEnvelope.seal(recipient,correlation,fields);}catch(Exception failure){throw new IllegalStateException("アクセス補助情報を暗号化できません。");}
        LocalDateTime now=LocalDateTime.ofInstant(clock.instant(),ZoneId.of("Asia/Tokyo"));
        try{jdbc.update("INSERT INTO security_access_evidence(correlation_id,source_key,occurred_at,producer,key_id,encrypted_detail) VALUES(?,?,?,'OA',?,?)",correlation,source,now,keyId,encrypted);}catch(org.springframework.dao.DuplicateKeyException existing){/* 同一要求の最初の証跡を保持 */}
        // 古い並行要求が最新情報を上書きしない。証跡は変更しない。
        jdbc.update("INSERT INTO security_source_profile(source_key,evidence_id,last_seen_at) VALUES(?,?,?) ON DUPLICATE KEY UPDATE evidence_id=IF(VALUES(last_seen_at)>=last_seen_at,VALUES(evidence_id),evidence_id),last_seen_at=GREATEST(last_seen_at,VALUES(last_seen_at))",source,correlation,now);
    }
}
