package com.campus.remarket.config;

import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Runs all startup data seeds in their declared order from one entry point. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class DataSeedCoordinator implements ApplicationRunner {
    private final List<DataSeedTask> seeds;

    public DataSeedCoordinator(List<DataSeedTask> seeds) {
        this.seeds = seeds;
    }

    @Override
    public void run(ApplicationArguments args) {
        seeds.forEach(DataSeedTask::initialize);
    }
}
