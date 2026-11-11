package com.campus.remarket.service;

import com.campus.remarket.dto.TradeDto;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

@Service
public class ReservationRequests {
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{1,80}");
    private final TradeService trades;
    private final JdbcTemplate db;
    private final ObjectMapper json;

    public ReservationRequests(TradeService trades, JdbcTemplate db, ObjectMapper json) {
        this.trades = trades;
        this.db = db;
        this.json = json;
    }

    @Transactional
    public TradeDto reserve(long memberId, String key, TradeDto request) {
        if (key == null) {
            return create(request); // Clients predating the request-key header.
        }
        if (!KEY.matcher(key).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "예약 요청 키 형식이 올바르지 않습니다.");
        }
        // The row already exists before the first request. Locking it also serializes
        // simultaneous reuse of one key for two *different* products.
        db.queryForObject("SELECT member_id FROM student WHERE member_id=? FOR UPDATE", Long.class, memberId);
        String payload = json.writeValueAsString(Arrays.asList(request.getSelno(), request.getMeetingPlace(),
                request.getMeetingTime(), decimal(request.getLatitude()), decimal(request.getLongitude())));
        var stored = db.query("SELECT request_payload, response_json FROM reservation_requests "
                        + "WHERE buyer_member_id=? AND request_key=?",
                (rs, row) -> new Saved(rs.getString(1), rs.getString(2)), memberId, key);
        if (!stored.isEmpty()) {
            var saved = stored.get(0);
            if (!saved.payload().equals(payload)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "같은 예약 요청 키에 다른 내용이 전달됐습니다.");
            }
            return json.readerFor(TradeDto.class).without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(saved.response());
        }
        var response = create(request);
        db.update("INSERT INTO reservation_requests (buyer_member_id,request_key,request_payload,order_id,response_json)"
                        + " VALUES (?,?,?,?,?)", memberId, key, payload, response.getOrderId(),
                json.writeValueAsString(response));
        return response;
    }

    private TradeDto create(TradeDto request) {
        trades.insert(request);
        return trades.selectOne(request.getOrderId());
    }

    private static String decimal(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toString();
    }

    private record Saved(String payload, String response) {}
}
