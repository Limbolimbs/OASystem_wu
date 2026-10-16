package com.example.control;

import com.example.audit.AccessFields;
import com.maxmind.db.Reader;
import java.io.*;
import java.net.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;

/** オフライン参照。接続元IPを第三者へ送信しない。 */
@Service
public class SourceGeography {
    private final Reader city,asn;
    public SourceGeography(@Value("${control.geoip-city:D:/oa-maintenance/geoip/city.mmdb}")String cityPath,
            @Value("${control.geoip-asn:D:/oa-maintenance/geoip/asn.mmdb}")String asnPath){city=reader(cityPath);asn=reader(asnPath);}
    private Reader reader(String path){try{return new File(path).isFile()?new Reader(new File(path),Reader.FileMode.MEMORY):null;}catch(IOException failure){return null;}}
    public static String addressType(InetAddress address){
        if(address.isLoopbackAddress())return "ループバック（本機）";
        if(address.isAnyLocalAddress())return "未指定アドレス";
        if(address.isLinkLocalAddress())return "リンクローカル";
        if(address.isSiteLocalAddress())return "プライベートネットワーク";
        if(address.isMulticastAddress())return "マルチキャスト";
        byte[] b=address.getAddress();
        if(b.length==4){int a=b[0]&255,c=b[1]&255,d=b[2]&255;
            if(a==100&&(c>=64&&c<=127))return "共有アドレス（CGNAT）";
            if(a==0||a>=240||(a==192&&c==0)||(a==192&&c==0&&d==2)||(a==198&&(c==18||c==19))||(a==198&&c==51&&d==100)||(a==203&&c==0&&d==113))return "予約・文書用アドレス";
        }else{
            if((b[0]&254)==252)return "プライベートネットワーク（ULA）";
            if((b[0]&255)==32&&(b[1]&255)==1&&(b[2]&255)==13&&(b[3]&255)==184)return "文書用アドレス";
        }
        return "公開アドレス";
    }
    private static Map<?,?> map(Object value){return value instanceof Map<?,?> m?m:Map.of();}
    private static String name(Object value){Map<?,?> names=map(map(value).get("names"));return Objects.toString(names.get("ja"),Objects.toString(names.get("en"),""));}
    public Map<String,Object> lookup(String ip){
        var result=new LinkedHashMap<String,Object>();InetAddress address=AccessFields.literal(ip);
        if(address==null){result.put("status","IP未取得");return result;}
        String type=addressType(address);result.put("addressType",type);result.put("family",ip.contains(":")?address instanceof Inet4Address?"IPv6表記（IPv4埋込み）":"IPv6":"IPv4");
        if(!type.equals("公開アドレス")){result.put("status","公開IPではないため地域推定なし");return result;}
        result.put("status",city==null?"地域データ未設定":"IPデータベースによる推定");
        result.put("warning","所在地・座標はIP範囲の代表値です。端末の現在地・住所・人物を示しません。");
        try{
            if(city!=null){
                Map<?,?> data=map(city.get(address,Map.class));
                result.put("country",name(data.get("country")));result.put("countryCode",map(data.get("country")).getOrDefault("iso_code",null));
                Object subdivisions=data.get("subdivisions");String region="";if(subdivisions instanceof List<?> list&&!list.isEmpty())region=name(list.get(0));
                result.put("region",region);result.put("city",name(data.get("city")));
                Map<?,?> location=map(data.get("location"));result.put("latitude",location.get("latitude"));result.put("longitude",location.get("longitude"));result.put("timeZone",location.get("time_zone"));
                result.put("cityDatabaseDate",city.getMetadata().buildTime().toString());
                if(data.isEmpty())result.put("status","地域データに該当なし");
            }
            if(asn!=null){Map<?,?> data=map(asn.get(address,Map.class));result.put("asn",data.get("autonomous_system_number"));result.put("organization",data.get("autonomous_system_organization"));result.put("asnDatabaseDate",asn.getMetadata().buildTime().toString());}
        }catch(IOException failure){result.clear();result.put("addressType",type);result.put("status","地域情報を取得できません。IPと監査を確認してください。");}
        return result;
    }
    @PreDestroy public void close(){for(Reader reader:new Reader[]{city,asn})if(reader!=null)try{reader.close();}catch(IOException ignored){}}
}
