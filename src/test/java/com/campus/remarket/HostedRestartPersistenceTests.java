package com.campus.remarket;

import com.campus.remarket.dto.TradeDto;
import com.campus.remarket.service.ReservationRequests;
import com.campus.remarket.service.StudentMemberLink;
import demo.campus.campusremarketmember.member.entity.Member;
import demo.campus.campusremarketmember.member.entity.School;
import demo.campus.campusremarketmember.member.entity.StudentStatus;
import demo.campus.campusremarketmember.member.repository.MemberRepository;
import demo.campus.campusremarketmember.member.repository.SchoolRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;

class HostedRestartPersistenceTests {
    @TempDir Path storage;

    @Test void hostedStartupKeepsOrdersAndMembersWithoutReseedingExamples() {
        String key = "hosted-restart-order";
        String orderId;
        long buyerId;
        long schoolCount;
        long notificationCount;
        try (var first = boot()) {
            var db = first.getBean(JdbcTemplate.class);
            assertEquals(0, count(db,"member"));
            assertEquals(0, count(db,"sales"));
            assertEquals(0, count(db,"shops"));
            assertEquals(10, count(db,"board_categories"));
            schoolCount = count(db,"school");
            var school = first.getBean(SchoolRepository.class).findByCampusCode("INHA").orElseThrow();
            var seller = student(first, school, "20269181");
            var buyer = student(first, school, "20269182");
            buyerId = buyer.getId();
            db.update("INSERT INTO sales (selno,school_id,seller,seller_member_id,category,name,oprice)"
                    + " VALUES (?,?,?,?,?,?,?)", 8001L, school.getId(), seller.getLoginId(),
                    seller.getId(), "전공서적", "재시작 검증 교재", 8200);
            var order = first.getBean(ReservationRequests.class).reserve(buyerId, key, request());
            orderId = order.getOrderId();
            notificationCount = count(db,"seller_notifications");
            assertEquals(2, notificationCount);
            db.update("UPDATE board_categories SET category_name='거래 후기' WHERE category_id=10");
        }
        assertTrue(Files.isRegularFile(storage.resolve("db/campus.mv.db")));

        try (var second = boot()) {
            var db = second.getBean(JdbcTemplate.class);
            assertEquals(2, count(db,"member")); // Only the two accounts created above.
            assertEquals(2, count(db,"student"));
            assertEquals(0, count(db,"shops"));
            assertEquals(schoolCount, count(db,"school"));
            assertEquals(10, count(db,"board_categories"));
            assertEquals("거래 후기", db.queryForObject("SELECT category_name FROM board_categories"
                    + " WHERE category_id=10", String.class));
            assertEquals(1, count(db,"sales"));
            assertEquals("예약중", db.queryForObject("SELECT status FROM sales WHERE selno=8001",String.class));
            var receipt = second.getBean(ReservationRequests.class).reserve(buyerId, key, request());
            assertEquals(orderId, receipt.getOrderId());
            assertEquals(8200, receipt.getAgreedPrice());
            assertEquals(1, count(db,"trade_orders"));
            assertEquals(1, count(db,"reservation_requests"));
            assertEquals(notificationCount, count(db,"seller_notifications"));
        }
    }

    private ConfigurableApplicationContext boot() {
        return new SpringApplicationBuilder(CampusApplication.class).profiles("hosted")
                .registerShutdownHook(false)
                .run("--server.port=0","--server.address=127.0.0.1",
                        "--CAMPUS_STORAGE_DIR="+storage.toAbsolutePath().toString().replace('\\','/'),
                        "--CAMPUS_DB_PASSWORD=isolated-test-store",
                        "--campus.notifications.enabled=false");
    }

    private Member student(ConfigurableApplicationContext app, School school, String number) {
        var encoded = app.getBean(PasswordEncoder.class).encode("test-fixture-only");
        var member = app.getBean(MemberRepository.class).saveAndFlush(Member.student(number,encoded,
                "검증 학생",number+"@example.test","010-0000-0000",StudentStatus.ENROLLED,school));
        app.getBean(StudentMemberLink.class).link(member);
        return member;
    }

    private TradeDto request() {
        var trade = new TradeDto();
        trade.setSelno(8001L);
        trade.setBuyer("20269182");
        trade.setMeetingPlace("정문");
        return trade;
    }

    private long count(JdbcTemplate db, String table) {
        return db.queryForObject("SELECT COUNT(*) FROM "+table, Long.class);
    }
}
