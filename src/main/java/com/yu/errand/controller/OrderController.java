package com.yu.errand.controller;

import com.yu.errand.common.ApiResponse;
import com.yu.errand.controller.dto.ActionRequest;
import com.yu.errand.controller.dto.CreateOrderRequest;
import com.yu.errand.controller.dto.GrabResult;
import com.yu.errand.domain.model.ErrandOrder;
import com.yu.errand.service.GrabService;
import com.yu.errand.service.OrderService;
import com.yu.errand.service.SettlementService;
import com.yu.errand.security.CurrentUserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderService orders;
    private final GrabService grab;
    private final SettlementService settlement;
    private final CurrentUserService currentUser;

    public OrderController(OrderService orders, GrabService grab, SettlementService settlement, CurrentUserService currentUser) {
        this.orders = orders;
        this.grab = grab;
        this.settlement = settlement;
        this.currentUser = currentUser;
    }

    @PostMapping
    public ApiResponse<ErrandOrder> create(@Valid @RequestBody CreateOrderRequest request) {
        return ApiResponse.ok(orders.create(currentUser.id(), request.title(), request.detail(), request.rewardCents(), request.claimTtlSeconds()));
    }

    @PostMapping("/{id}/grab")
    public ApiResponse<GrabResult> grab(@PathVariable long id) {
        return ApiResponse.ok(grab.grab(id, currentUser.id()));
    }

    @PostMapping("/{id}/deliver")
    public ApiResponse<ErrandOrder> deliver(@PathVariable long id) {
        return ApiResponse.ok(orders.deliver(id, currentUser.id(), settlement));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<ErrandOrder> cancel(@PathVariable long id) {
        return ApiResponse.ok(orders.cancel(id, currentUser.id()));
    }

    @GetMapping("/{id}")
    public ApiResponse<ErrandOrder> get(@PathVariable long id) { return ApiResponse.ok(orders.get(id)); }

    @GetMapping
    public ApiResponse<List<ErrandOrder>> list(@RequestParam(defaultValue = "20") int limit,
                                               @RequestParam(defaultValue = "0") int offset) {
        return ApiResponse.ok(orders.list(limit, offset));
    }
}
