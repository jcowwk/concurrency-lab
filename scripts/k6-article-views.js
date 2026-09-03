/*
 * 게시글 조회수 부하 테스트 — 처리량 측정 + 조회수 유실이 없는지 검증.
 *
 * 실행:  k6 run scripts/k6-article-views.js
 * 옵션:  k6 run -e VUS=100 -e ITERATIONS=5000 scripts/k6-article-views.js
 *
 * setup()   게시글 1개 생성
 * default() 그 게시글에 조회수 +1 요청 (총 ITERATIONS 회)
 * 끝나고    flush 주기(약 2s) 후 psql 로 view_count == 요청 수 인지 확인
 */
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE || 'http://localhost:8080';
const ITERATIONS = Number(__ENV.ITERATIONS || 5000);

export const options = {
  scenarios: {
    views: {
      executor: 'shared-iterations',
      vus: Number(__ENV.VUS || 100),
      iterations: ITERATIONS,
      maxDuration: '60s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<200'],
  },
};

export function setup() {
  const res = http.post(`${BASE}/api/articles`, JSON.stringify({ title: 'load-test', content: 'c' }), {
    headers: { 'Content-Type': 'application/json' },
  });
  check(res, { 'article created': (r) => r.status === 201 });
  const articleId = Number(res.body);
  console.log(`articleId=${articleId}, target views=${ITERATIONS}`);
  return { articleId };
}

export default function (data) {
  const res = http.post(`${BASE}/api/articles/${data.articleId}/views`);
  check(res, { '200': (r) => r.status === 200 });
}

export function teardown(data) {
  console.log('--- 검증 (flush 주기 ~2s 기다린 뒤 psql) ---');
  console.log(`sleep 3 && docker exec concurrency-lab-postgres psql -U lab -d concurrency_lab -c \\`);
  console.log(`  "SELECT view_count FROM article WHERE id=${data.articleId};"`);
  console.log(`→ ${ITERATIONS} 이면 유실 0 (main). naive 면 한참 작다.`);
}
