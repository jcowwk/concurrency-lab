package com.example.concurrencylab.coupon.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateCouponRequest(
        @NotBlank String name,
        @NotNull @Positive Integer totalQuantity
) {
}
