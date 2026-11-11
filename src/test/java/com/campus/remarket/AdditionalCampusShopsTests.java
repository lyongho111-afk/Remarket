package com.campus.remarket;

import com.campus.remarket.config.AdditionalCampusShopsSeed;
import com.campus.remarket.dto.ShopDto;
import com.campus.remarket.service.ShopPhotos;
import demo.campus.campusremarketmember.member.entity.*;
import demo.campus.campusremarketmember.member.repository.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:additionalshops;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "campus.notifications.enabled=false"})
class AdditionalCampusShopsTests {
    @Autowired AdditionalCampusShopsSeed seed;@Autowired JdbcTemplate db;
    @Autowired MemberRepository members;@Autowired SchoolRepository schools;@Autowired PasswordEncoder passwords;
    @LocalServerPort int port;final JsonMapper json=JsonMapper.builder().build();
    HttpClient browser(){return HttpClient.newBuilder().cookieHandler(new CookieManager()).build();}
    JsonNode call(HttpClient c,String method,String path,Object data,String token,int expected)throws Exception{
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));SecurityTestClient.csrf(c,b,method,path,port);
        if(data!=null)b.header("Content-Type","application/json");if(token!=null)b.header("X-CSRF-Token",token);
        var r=c.send(b.method(method,data==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(data))).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(expected,r.statusCode(),path+": "+r.body());return r.body().isBlank()?null:json.readTree(r.body());
    }
    HttpClient login(String id,String role,Long school)throws Exception{
        var c=browser();var input=new HashMap<String,Object>();input.put("loginId",id);input.put("role",role);input.put("password","campus1234!");if(school!=null)input.put("schoolId",school);
        call(c,"POST","/api/members/login",input,null,200);return c;
    }
    @Test void existingListingUsesItsOwnUploadAndDoesNotBorrowFromAnotherBranch(){
        var shop = new ShopDto();
        shop.setShopname("포케품은초밥 인하대점");
        shop.setAddress("인천 미추홀구 인하로77번길 6-29 1층");
        assertEquals("menu-sushi-roll.jpg", ShopPhotos.forDisplay(shop));
        String upload = "c7485761-8716-4e92-8641-3bdf69187de4.png";
        shop.setPhoto(upload);
        assertEquals(upload, ShopPhotos.forDisplay(shop));
        shop.setPhoto(null);
        shop.setAddress("다른 지점 주소");
        assertNull(ShopPhotos.forDisplay(shop));
    }
    @Test void elevenStoresOwnAccountsAndSchoolVisibilityPreserveDataAndOwnerSettings()throws Exception{
        var originalShops=db.queryForList("SELECT * FROM shops ORDER BY shopId");
        var originalSales=db.queryForList("SELECT * FROM sales ORDER BY selno");
        var originalMembers=db.queryForList("SELECT * FROM member ORDER BY id");
        seed.seed();
        assertEquals(originalShops.size()+11,db.queryForObject("SELECT COUNT(*) FROM shops",Integer.class));
        assertEquals(originalMembers.size()+11,db.queryForObject("SELECT COUNT(*) FROM member",Integer.class));
        for(var before:originalShops)assertEquals(before,db.queryForMap("SELECT * FROM shops WHERE shopId=?",before.get("SHOPID")));
        for(var before:originalMembers)assertEquals(before,db.queryForMap("SELECT * FROM member WHERE id=?",before.get("ID")));
        assertEquals(originalSales,db.queryForList("SELECT * FROM sales ORDER BY selno"));
        assertEquals(11,db.queryForObject("SELECT COUNT(*) FROM shops s JOIN member m ON m.id=s.owner_member_id WHERE m.login_id LIKE 'owner_inu%' OR m.login_id LIKE 'owner_chung%'",Integer.class));
        String[][] groups={{"INU","owner_inu", "5"},{"CHUNGWOON","owner_chung","6"}};
        var catalogPhotos = new HashSet<String>();
        for(var group:groups){
            var school=schools.findByCampusCode(group[0]).orElseThrow();
            int count=Integer.parseInt(group[2]);
            var pupil=members.saveAndFlush(Member.student(group[0].equals("INU")?"20269961":"20269962",passwords.encode("campus1234!"),"가게조회 학생",group[0]+"-shops@example.invalid",null,StudentStatus.ENROLLED,school));
            var student=login(pupil.getLoginId(),"STUDENT",school.getId());var visible=call(student,"GET","/api/shops",null,null,200);assertEquals(count,visible.size());
            var publicPhotos=new HashMap<String,String>();for(var s:visible)publicPhotos.put(s.get("title").asText(),s.get("photo").asText());
            for(int i=1;i<=count;i++){
                String id=group[1]+String.format("%02d",i);var owner=login(id,"OWNER",null);var home=call(owner,"GET","/api/owner/dashboard",null,null,200);var shop=home.get("shop");
                String photo = shop.get("photo").asText();
                assertEquals(publicPhotos.get(shop.get("name").asText()), photo);
                assertTrue(catalogPhotos.add(photo), "가게별 대표사진이 겹칩니다: " + photo);
                var image = student.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/shops/photo/" + photo)).build(), HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(200, image.statusCode());
                assertEquals("image/jpeg", image.headers().firstValue("content-type").orElse(""));
                assertEquals(0xff, Byte.toUnsignedInt(image.body()[0]));
                assertEquals(0xd8, Byte.toUnsignedInt(image.body()[1]));
                assertTrue(shop.get("latitude").asDouble()>37&&shop.get("latitude").asDouble()<38);
                assertTrue(shop.get("longitude").asDouble()>126&&shop.get("longitude").asDouble()<127);
                assertEquals(school.getId(),members.findByAccountKey("staff:"+id).orElseThrow().getSchool().getId());
                if(id.equals("owner_inu01")){
                    call(owner,"PUT","/api/owner/shop/status",Map.of("status","영업중","version",shop.get("version").asLong()),home.get("csrfToken").asText(),200);
                    var next=call(student,"GET","/api/shops",null,null,200);boolean changed=false;for(var s:next)if(s.get("id").asLong()==shop.get("id").asLong())changed=s.get("status").asText().equals("영업중");assertTrue(changed);
                }
            }
        }
        assertEquals("",db.queryForObject("SELECT s.shopphone FROM shops s JOIN member m ON m.id=s.owner_member_id WHERE m.login_id='owner_chung06'",String.class));
        var changed=db.queryForMap("SELECT s.* FROM shops s JOIN member m ON m.id=s.owner_member_id WHERE m.login_id='owner_inu01'");
        seed.seed();assertEquals(changed,db.queryForMap("SELECT s.* FROM shops s JOIN member m ON m.id=s.owner_member_id WHERE m.login_id='owner_inu01'"));
        assertEquals(originalShops.size()+11,db.queryForObject("SELECT COUNT(*) FROM shops",Integer.class));
        var inha=login("20261234","STUDENT",schools.findByCampusCode("INHA").orElseThrow().getId());
        var list=call(inha,"GET","/api/shops",null,null,200);assertEquals(originalShops.size(),list.size());
        long foreign=((Number)changed.get("SHOPID")).longValue();call(inha,"GET","/shops/selectOne?id="+foreign,null,null,404);
    }
}
