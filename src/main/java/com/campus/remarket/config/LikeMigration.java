package com.campus.remarket.config;
import com.campus.remarket.service.EngagementService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class LikeMigration implements ApplicationRunner {
    private final EngagementService engagement;
    public LikeMigration(EngagementService engagement){this.engagement=engagement;}
    public void run(ApplicationArguments args){engagement.consolidateBookmarks();}
}
