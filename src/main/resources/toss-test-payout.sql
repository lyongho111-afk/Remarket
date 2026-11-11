-- Only references and verified test statuses are retained. No bank account data.
CREATE TABLE IF NOT EXISTS toss_test_seller_links (
    member_id BIGINT PRIMARY KEY REFERENCES student(member_id),
    seller_id VARCHAR(64) NOT NULL UNIQUE,
    ref_seller_id VARCHAR(64) NOT NULL UNIQUE
);
CREATE TABLE IF NOT EXISTS toss_test_payout_links (
    order_id VARCHAR(64) PRIMARY KEY REFERENCES trade_orders(order_id),
    member_id BIGINT NOT NULL REFERENCES toss_test_seller_links(member_id),
    payout_id VARCHAR(64) NOT NULL UNIQUE,
    amount INT NOT NULL CHECK (amount > 0),
    status VARCHAR(24) NOT NULL,
    verified_at TIMESTAMP NOT NULL
);
