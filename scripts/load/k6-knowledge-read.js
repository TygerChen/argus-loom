// 知识库读路径压测：列表 / 详情 / 下载（占 70% 流量）。
// 用法: k6 run -e BASE_URL=https://staging.example.com -e TOKEN=<jwt> scripts/load/k6-knowledge-read.js
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
    vus: 100,
    duration: '30m',
    thresholds: {
        http_req_failed: ['rate<0.01'],
        http_req_duration: ['p(95)<500'],
    },
};

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const TOKEN = __ENV.TOKEN || '';
const headers = { headers: { Authorization: `Bearer ${TOKEN}` } };

export default function () {
    const list = http.get(`${BASE}/api/documents?pageNum=1&pageSize=20`, headers);
    check(list, { 'list 200': (r) => r.status === 200 });

    const detail = http.get(`${BASE}/api/documents/1`, headers);
    check(detail, { 'detail ok': (r) => [200, 403, 404].includes(r.status) });

    sleep(1);
}
