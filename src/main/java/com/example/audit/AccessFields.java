package com.example.audit;

import java.net.*;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;

/** 収集する項目を列挙。要求本文・認証コード・Cookie・クエリは参照しない。 */
public final class AccessFields {
    private AccessFields() {}
    public static String clean(String value,int limit){
        if(value==null)return "";
        return value.codePoints().filter(c->!Character.isISOControl(c)).limit(limit).collect(StringBuilder::new,StringBuilder::appendCodePoint,StringBuilder::append).toString();
    }
    public static InetAddress literal(String value){
        if(value==null||value.length()>64||!value.matches("[0-9a-fA-F:.]+(?:%[A-Za-z0-9_.-]{1,16})?"))return null;
        try{
            // 数字のリテラルだけを受け付け、DNS参照やURL取得を行わない。
            if(value.contains(":")){String numeric=value.contains("%")?value.substring(0,value.indexOf('%')):value;return InetAddress.ofLiteral(numeric);}
            String[] parts=value.split("\\.",-1);if(parts.length!=4)return null;
            byte[] bytes=new byte[4];for(int n=0;n<4;n++){if(!parts[n].matches("[0-9]{1,3}"))return null;int b=Integer.parseInt(parts[n]);if(b>255)return null;bytes[n]=(byte)b;}
            return InetAddress.getByAddress(bytes);
        }catch(Exception failure){return null;}
    }
    public static Map<String,String> capture(HttpServletRequest request){
        var fields=new LinkedHashMap<String,String>();
        if(literal(request.getRemoteAddr())!=null)fields.put("ip",request.getRemoteAddr());
        fields.put("peerPort",Integer.toString(request.getRemotePort()));
        fields.put("method",clean(request.getMethod(),12));
        String path=request.getRequestURI();int query=path==null?-1:path.indexOf('?');if(query>=0)path=path.substring(0,query);
        if(path!=null)path=path.replaceAll("(?i)(?:;|%3b)[^/]*","");
        fields.put("path",clean(path,500));fields.put("userAgent",clean(request.getHeader("User-Agent"),1000));
        fields.put("language",clean(request.getHeader("Accept-Language"),100));
        fields.put("scheme",request.isSecure()?"HTTPS":"HTTP");fields.put("protocol",clean(request.getProtocol(),16));
        fields.put("tlsCipher",clean(Objects.toString(request.getAttribute("jakarta.servlet.request.cipher_suite"),""),100));
        String loginPath=request.getContextPath()==null?"":request.getContextPath();
        if("POST".equals(request.getMethod())&&(Objects.equals(path,loginPath+"/users/login")||Objects.equals(path,loginPath+"/auth/login"))){
            String name=request.getParameter(path.endsWith("/users/login")?"userName":"operator");
            fields.put("attemptedLogin",name!=null&&name.trim().matches("[A-Za-z0-9_-]{1,50}")?name.trim():"形式外・未入力");
        }
        fields.put("ipSource","接続ソケット（転送ヘッダー不使用）");return Map.copyOf(fields);
    }
}
