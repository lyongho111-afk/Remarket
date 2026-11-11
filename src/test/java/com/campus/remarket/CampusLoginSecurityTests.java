package com.campus.remarket;

import com.campus.remarket.service.AccountService;
import demo.campus.campusremarketmember.member.entity.Member;
import demo.campus.campusremarketmember.member.entity.School;
import demo.campus.campusremarketmember.member.entity.StudentStatus;
import demo.campus.campusremarketmember.member.repository.MemberRepository;
import demo.campus.campusremarketmember.member.repository.SchoolRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.datasource.url=jdbc:h2:mem:campus_login_security;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "campus.notifications.initial-delay-ms=3600000"})
@Import(CampusLoginSecurityTests.PrincipalProbe.class)
class CampusLoginSecurityTests {
    @LocalServerPort int port;
    @Autowired MemberRepository members;
    @Autowired SchoolRepository schools;
    @Autowired PasswordEncoder passwords;
    @Autowired JdbcTemplate db;
    final JsonMapper json = JsonMapper.builder().build();

    // An actual MVC request reads Spring Security's principal and persisted context.
    // @TestComponent keeps this probe out of ordinary application component scans.
    @RestController
    @TestComponent
    static class PrincipalProbe {
        @GetMapping("/_test/principal")
        Map<String,Object> principal(Authentication auth, HttpServletRequest request) {
            if (auth == null) return Map.of("authenticated", false);
            var session = request.getSession(false);
            SecurityContext saved = session == null ? null : (SecurityContext) session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
            return Map.of("authenticated", auth.isAuthenticated(), "name", auth.getName(),
                "authorities", auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList(),
                "sessionName", saved == null || saved.getAuthentication() == null ? "" : saved.getAuthentication().getName());
        }
    }

    private class Browser {
        final CookieManager cookies = new CookieManager();
        final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();

        HttpResponse<String> request(String path, Map<String,Object> post, int expected) throws Exception {
            var url = URI.create("http://127.0.0.1:" + port + path);
            var builder = HttpRequest.newBuilder(url);
            if (post != null) {
                SecurityTestClient.csrf(http, builder, "POST", path, port);
                builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(post)));
            }
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(expected,response.statusCode(),path + " " + response.body());
            return response;
        }

        JsonNode get(String path) throws Exception {
            return json.readTree(request(path,null,200).body());
        }

        JsonNode login(String id, Long schoolId, String password, String role, int expected) throws Exception {
            var payload = new LinkedHashMap<String,Object>();
            payload.put("loginId",id);
            payload.put("password",password);
            payload.put("role",role);
            if (schoolId != null) payload.put("schoolId",schoolId);
            return json.readTree(request("/api/members/login",payload,expected).body());
        }

        String sessionId() {
            return cookies.getCookieStore().getCookies().stream()
                    .filter(c -> "JSESSIONID".equals(c.getName())).map(HttpCookie::getValue).findFirst().orElseThrow();
        }

        void assertPrincipal(String name, String role) throws Exception {
            JsonNode principal = get("/_test/principal");
            assertTrue(principal.get("authenticated").asBoolean());
            assertEquals(name, principal.get("name").asText());
            assertEquals(name, principal.get("sessionName").asText());
            assertTrue(principal.get("authorities").valueStream().anyMatch(a -> a.asText().equals("ROLE_" + role)));
        }
    }

    private int replaySession(String id) throws Exception {
        var url = URI.create("http://127.0.0.1:" + port + "/api/members/me");
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(url).header("Cookie","JSESSIONID=" + id).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test void successfulLoginRotatesSessionAndLogoutInvalidatesReplayedCookie() throws Exception {
        var client = new Browser();
        client.get("/api/security/csrf");
        String anonymousCookie = client.sessionId();
        var member = client.login("owner1",null,"campus1234!","OWNER",200);
        String signedInCookie = client.sessionId();
        assertNotEquals(anonymousCookie,signedInCookie);
        assertEquals(401,replaySession(anonymousCookie));
        client.assertPrincipal("staff:owner1","OWNER");
        assertEquals(member.get("id").asLong(), client.get("/api/members/me").get("id").asLong());
        client.request("/api/members/logout",Map.of(),200);
        assertEquals(401,replaySession(signedInCookie));
        assertFalse(client.get("/_test/principal").get("authenticated").asBoolean());
    }

    private Member student(String number, String secret, School school) {
        return members.saveAndFlush(Member.student(number,passwords.encode(secret),"테스트 학생",
                number + "-" + school.getId() + "@example.invalid",null,StudentStatus.ENROLLED,school));
    }

    @Test void sameLoginIdResolvesDistinctStudentsAndStaffAndCannotCrossScopes() throws Exception {
        String id = "73504192";
        var inha = schools.findByCampusCode("INHA").orElseThrow();
        var inu = schools.findByCampusCode("INU").orElseThrow();
        var a = student(id,"Inha7350!",inha);
        var b = student(id,"Inu7350!",inu);
        var staff = members.saveAndFlush(Member.admin(id,passwords.encode("Staff7350!"),"테스트 운영자",null));
        assertTrue(a.getPassword().startsWith("$2"));
        assertTrue(passwords.matches("Inha7350!",a.getPassword()));

        var client = new Browser();
        client.login(id,inu.getId(),"Inha7350!","STUDENT",401);
        client.login(id,null,"Inu7350!","ADMIN",401);
        client.login(id,inha.getId(),a.getPassword(),"STUDENT",401);
        client.request("/api/members/me",null,401);
        assertEquals(a.getId().longValue(), client.login(id,inha.getId(),"Inha7350!","STUDENT",200).get("id").asLong());
        client.assertPrincipal(a.getAccountKey(),"STUDENT");
        client.request("/api/admin/dashboard",null,403);
        assertEquals(b.getId().longValue(), client.login(id,inu.getId(),"Inu7350!","STUDENT",200).get("id").asLong());
        client.assertPrincipal(b.getAccountKey(),"STUDENT");
        assertEquals(staff.getId().longValue(), client.login(id,null,"Staff7350!","ADMIN",200).get("id").asLong());
        client.assertPrincipal(staff.getAccountKey(),"ADMIN");
        client.request("/api/admin/dashboard",null,200);
    }

    @Test void demotionStopsAdminAccessWithTheSameBrowserCookie() throws Exception {
        Member m = members.saveAndFlush(Member.admin("role-change-probe",passwords.encode("Role1234!"),"운영자",null));
        var client = new Browser();
        client.login(m.getLoginId(),null,"Role1234!","ADMIN",200);
        client.request("/api/admin/dashboard",null,200);
        db.update("UPDATE member SET role='OWNER' WHERE id=?",m.getId());
        client.request("/api/admin/dashboard",null,403);
        client.assertPrincipal(m.getAccountKey(),"OWNER");
    }

    @Test void passwordChangeRevokesOtherBrowserAndOldPassword() throws Exception {
        Member m = members.saveAndFlush(Member.admin("revoke-probe",passwords.encode("Before123!"),"운영자",null));
        try {
            var a = new Browser();
            var b = new Browser();
            a.login(m.getLoginId(),null,"Before123!","ADMIN",200);
            b.login(m.getLoginId(),null,"Before123!","ADMIN",200);
            assertNotEquals(a.sessionId(),b.sessionId());
            a.request("/api/account/password",Map.of("oldPassword","Before123!","password","After456!"),200);
            b.request("/api/admin/dashboard",null,401);
            a.login(m.getLoginId(),null,"Before123!","ADMIN",401);
            a.login(m.getLoginId(),null,"After456!","ADMIN",200);
            a.assertPrincipal(m.getAccountKey(),"ADMIN");
        } finally {
            AccountService.REVOKED.remove(m.getId());
        }
    }
}
