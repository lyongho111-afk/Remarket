package com.campus.remarket.config;

/** A startup task that inserts or updates initial reference/demo data. */
public interface DataSeedTask {
    void initialize();
}
