package com.example.concurrencylab.coupon.api;

import com.example.concurrencylab.coupon.api.dto.CreateCouponRequest;
import com.example.concurrencylab.coupon.api.dto.IssueCouponRequest;
import com.example.concurrencylab.coupon.service.CouponIssueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "쿠폰", description = "선착순 한정수량 쿠폰 발급")
@RestController
@RequestMapping("/api/coupons")
@RequiredArgsConstructor
public class CouponController {

    private final CouponIssueService couponIssueService;

    @Operation(summary = "쿠폰 생성", description = "테스트/데모용. 재고(totalQuantity)를 지정해 캠페인 1개를 만든다. 생성된 couponId 반환.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Long create(@Valid @RequestBody CreateCouponRequest request) {
        return couponIssueService.createCoupon(request.name(), request.totalQuantity());
    }

    @Operation(summary = "쿠폰 발급",
            description = "couponId 쿠폰을 userId 에게 발급. 재고 소진 시 409, 이미 받은 유저면 409, 없는 쿠폰이면 404, Redis 장애 시 503.")
    @PostMapping("/issue")
    @ResponseStatus(HttpStatus.OK)
    public void issue(@Valid @RequestBody IssueCouponRequest request) {
        couponIssueService.issue(request.couponId(), request.userId());
    }

    @Operation(summary = "[운영] Redis 상태 재구축",
            description = "Redis 데이터 유실 후 복구용. coupon_issue 이력으로 coupon:stock / coupon:issued 를 다시 만든다. 발급이 잠잠할 때 실행.")
    @PostMapping("/{id}/redis-rebuild")
    @ResponseStatus(HttpStatus.OK)
    public void rebuildRedis(@PathVariable Long id) {
        couponIssueService.rebuildRedisState(id);
    }
}
