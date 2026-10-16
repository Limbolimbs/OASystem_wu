package com.example.control;

import static org.junit.jupiter.api.Assertions.*;
import com.example.audit.AccessFields;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class SourceGeographyTest {
    @Test void privateAndReservedIpsAreNotMisrepresentedAsLocations(){
        var lookup=new SourceGeography("missing-city","missing-asn");
        for(String address:new String[]{"127.0.0.1","::1","10.1.2.3","192.168.1.1","100.64.0.1","2001:db8::2","fc00::1","169.254.2.3"})assertNotEquals("公開アドレス",lookup.lookup(address).get("addressType"));
        assertEquals("公開アドレス",SourceGeography.addressType(AccessFields.literal("8.8.8.8")));
        assertEquals("IP未取得",lookup.lookup("<img src=x>").get("status"));lookup.close();
    }
    @Test @EnabledIfEnvironmentVariable(named="CONTROL_GEO_TEST",matches="true")
    void downloadedDataResolvesExamplePublicIpsOffline(){
        var lookup=new SourceGeography("D:/oa-maintenance/geoip/city.mmdb","D:/oa-maintenance/geoip/asn.mmdb");
        try{var result=lookup.lookup("8.8.8.8");assertNotNull(result.get("countryCode"));assertNotNull(result.get("city"));assertNotNull(result.get("asn"));assertNotNull(result.get("organization"));assertNotNull(result.get("cityDatabaseDate"));assertNotNull(lookup.lookup("2001:4860:4860::8888").get("asn"));}finally{lookup.close();}
    }
}
