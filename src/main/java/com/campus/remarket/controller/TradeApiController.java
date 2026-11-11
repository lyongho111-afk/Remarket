package com.campus.remarket.controller;
import com.campus.remarket.auth.MarketMember;
import com.campus.remarket.dto.TradeDto;
import com.campus.remarket.service.TradeService;
import com.campus.remarket.service.ReservationRequests;
import java.util.List;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/trades")
public class TradeApiController {
    private final MarketMember member;
    private final TradeService service;
    private final ReservationRequests reservations;
    public TradeApiController(MarketMember member,TradeService service,ReservationRequests reservations){this.member=member;this.service=service;this.reservations=reservations;}
    @GetMapping public List<TradeDto> selectAll(){return member.visibleTrades();}
    @PostMapping public TradeDto insert(@RequestBody TradeDto dto,
            @RequestHeader(value="Idempotency-Key",required=false) String requestKey) {
        member.reserve(dto.getSelno());
        dto.setBuyer(member.studentKey());
        return reservations.reserve(member.current().getId(), requestKey, dto);
    }
    @PostMapping("/{id}/cancel") public TradeDto cancel(@PathVariable String id){member.trade(id);service.cancel(id);return service.selectOne(id);}
    @PostMapping("/{id}/complete") public TradeDto complete(@PathVariable String id){member.trade(id);service.complete(id);return service.selectOne(id);}
    @PutMapping("/{id}/meeting") public TradeDto meeting(@PathVariable String id,@RequestBody TradeDto dto){member.trade(id);dto.setOrderId(id);service.meeting(dto);return service.selectOne(id);}
}
