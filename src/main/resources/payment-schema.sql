CREATE TABLE IF NOT EXISTS payment_receipts (
    order_id VARCHAR(64) PRIMARY KEY REFERENCES trade_orders(order_id),
    payment_key VARCHAR(200) NOT NULL UNIQUE,
    payment_mode VARCHAR(4) NOT NULL CHECK(payment_mode IN ('TEST','LIVE')),
    total_amount INT NOT NULL CHECK(total_amount > 0),
    balance_amount INT NOT NULL CHECK(balance_amount >= 0 AND balance_amount <= total_amount),
    status VARCHAR(24) NOT NULL CHECK(status IN ('DONE','PARTIAL_CANCELED','CANCELED','ABORTED','EXPIRED')),
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS payment_events (
    snapshot_id CHAR(64) PRIMARY KEY,
    order_id VARCHAR(64) NOT NULL REFERENCES payment_receipts(order_id),
    status VARCHAR(24) NOT NULL,
    balance_amount INT NOT NULL,
    recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS toss_customer_keys (
    buyer VARCHAR(50) PRIMARY KEY REFERENCES student(unisno),
    customer_key VARCHAR(36) NOT NULL UNIQUE
);
CREATE TABLE IF NOT EXISTS toss_escrow_orders (
    order_id VARCHAR(64) PRIMARY KEY REFERENCES trade_orders(order_id),
    escrow_id VARCHAR(200) UNIQUE,
    product_id VARCHAR(200) UNIQUE,
    status VARCHAR(25) NOT NULL,
    delivery_id VARCHAR(200) UNIQUE,
    received_at TIMESTAMP WITH TIME ZONE,
    receiver_name VARCHAR(50),
    verified_at TIMESTAMP
);
