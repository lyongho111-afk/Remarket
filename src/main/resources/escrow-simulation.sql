-- One virtual seller ledger entry per verified test payment. Amounts are KRW.
CREATE TABLE IF NOT EXISTS escrow_simulations (
    order_id VARCHAR(64) PRIMARY KEY REFERENCES trade_orders(order_id),
    seller VARCHAR(50) NOT NULL,
    amount INT NOT NULL CHECK (amount > 0),
    balance INT NOT NULL CHECK (balance >= 0 AND balance <= amount),
    state VARCHAR(24) NOT NULL CHECK (state IN ('HELD','CONFIRMED','SETTLED','PARTIALLY_REFUNDED','REFUNDED')),
    held_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    confirmed_at TIMESTAMP,
    settled_at TIMESTAMP
);
