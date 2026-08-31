package com.example.concurrencylab.coupon.api.dto;

import jakarta.validation.constraints.NotNull;

public record IssueCouponRequest(
        @NotNull Long couponId,
        @NotNull Long userId
) {
}
