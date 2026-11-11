package com.campus.remarket.escrow;

/** Published within the payment transaction after the authoritative receipt is stored. */
public record PaymentBalanceChanged(String orderId) {}
