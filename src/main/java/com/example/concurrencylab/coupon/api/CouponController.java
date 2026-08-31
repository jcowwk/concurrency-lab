package com.example.concurrencylab.coupon.api;

import com.example.concurrencylab.coupon.api.dto.CreateCouponRequest;
import com.example.concurrencylab.coupon.api.dto.IssueCouponRequest;
import com.example.concurrencylab.coupon.service.CouponIssueService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/coupons")
@RequiredArgsConstructor
public class CouponController {

    private final CouponIssueService couponIssueService;

    /** 테스트/데모용 쿠폰 생성 (예: 수량 100) */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Long create(@Valid @RequestBody CreateCouponRequest request) {
        return couponIssueService.createCoupon(request.name(), request.totalQuantity());
    }

    @PostMapping("/issue")
    @ResponseStatus(HttpStatus.OK)
    public void issue(@Valid @RequestBody IssueCouponRequest request) {
        couponIssueService.issue(request.couponId(), request.userId());
    }
}
