/*
 * 쿠폰 발급 부하 테스트 — 초과 발급이 안 나는지 검증.
 *
 * 실행:  k6 run scripts/k6-coupon-issue.js
 * 옵션:  k6 run -e VUS=300 -e DURATION=20s -e STOCK=100 scripts/k6-coupon-issue.js
 *
 * setup()   재고 STOCK 짜리 쿠폰 1개 생성 → couponId 를 default 로 전달
 * default() VU/반복마다 다른 userId 로 발급 요청 (초과 발급 시나리오)
 * 끝나고    아래 psql 로 issued_count / 이력 수가 STOCK 을 안 넘는지 확인
 */
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE || 'http://localhost:8080';
const STOCK = Number(__ENV.STOCK || 100);

export const options = {
  vus: Number(__ENV.VUS || 200),
  duration: __ENV.DURATION || '15s',
  thresholds: {
    // 5xx(서버 에러)는 없어야 한다. 409(소진/중복)는 정상이므로 실패 아님.
    'http_req_failed{expected_response:true}': ['rate<0.01'],
  },
};

export function setup() {
  const res = http.post(`${BASE}/api/coupons`, JSON.stringify({ name: 'load-test', totalQuantity: STOCK }), {
    headers: { 'Content-Type': 'application/json' },
  });
  check(res, { 'coupon created': (r) => r.status === 201 });
  const couponId = Number(res.body);
  console.log(`couponId=${couponId}, stock=${STOCK}`);
  return { couponId };
}

export default function (data) {
  const userId = __VU * 1000000 + __ITER; // VU/반복마다 유일
  const res = http.post(
    `${BASE}/api/coupons/issue`,
    JSON.stringify({ couponId: data.couponId, userId }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  // 200=발급 성공, 409=소진/중복(정상 거부). 5xx 만 진짜 실패.
  check(res, { 'no 5xx': (r) => r.status < 500 });
}

export function teardown(data) {
  console.log('--- 검증 (psql) ---');
  console.log(`docker exec concurrency-lab-postgres psql -U lab -d concurrency_lab -c \\`);
  console.log(`  "SELECT issued_count FROM coupon WHERE id=${data.couponId};`);
  console.log(`   SELECT count(*) FROM coupon_issue WHERE coupon_id=${data.couponId};"`);
  console.log(`둘 다 ${STOCK} 이하 & 서로 같아야 정상 (main). naive 면 이력이 ${STOCK} 을 넘어간다.`);
}
