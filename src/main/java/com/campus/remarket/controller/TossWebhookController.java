package com.campus.remarket.controller;

import com.campus.remarket.service.PaymentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class TossWebhookController {
    public record Data(String orderId, String paymentKey) {}
    public record Notification(String eventType, Data data) {}
    private final PaymentService payments;
    public TossWebhookController(PaymentService payments) { this.payments=payments; }

    @PostMapping("/api/payments/webhook/toss")
    public ResponseEntity<Void> receive(@RequestBody Notification notice) {
        if (!"PAYMENT_STATUS_CHANGED".equals(notice.eventType())) return ResponseEntity.ok().build();
        if (notice.data()==null) return ResponseEntity.badRequest().build();
        try {
            payments.reconcile(notice.data().orderId(),notice.data().paymentKey());
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().build();
        } catch (IllegalStateException ex) {
            // Do not acknowledge unverified payments; Toss retries failed deliveries.
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }
}
