package com.yu.errand.controller;

import com.yu.errand.common.ApiResponse;
import com.yu.errand.controller.dto.PaymentCallbackRequest;
import com.yu.errand.controller.dto.PaymentView;
import com.yu.errand.controller.dto.RefundRequest;
import com.yu.errand.controller.dto.RefundView;
import com.yu.errand.security.CurrentUserService;
import com.yu.errand.service.PaymentService;
import com.yu.errand.service.RefundService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {
    private final PaymentService payments;
    private final RefundService refunds;
    private final CurrentUserService currentUser;

    public PaymentController(PaymentService payments, RefundService refunds, CurrentUserService currentUser) {
        this.payments = payments;
        this.refunds = refunds;
        this.currentUser = currentUser;
    }

    @PostMapping("/webhook")
    public ApiResponse<PaymentView> webhook(@Valid @RequestBody PaymentCallbackRequest request) {
        return ApiResponse.ok(payments.callback(request));
    }

    @GetMapping("/{id}")
    public ApiResponse<PaymentView> get(@PathVariable long id) {
        return ApiResponse.ok(payments.get(id, currentUser.id(), currentUser.isAdmin()));
    }

    @PostMapping("/{id}/refund")
    public ApiResponse<RefundView> refund(@PathVariable long id, @Valid @RequestBody RefundRequest request) {
        return ApiResponse.ok(refunds.refund(id, currentUser.id(), request.amountCents(), request.requestKey()));
    }
}
