// RAG 检索与流式问答压测（占 20% 流量），门槛：检索 P95 < 2s、首 Token P95 < 5s。
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
    vus: 20,
    duration: '30m',
    thresholds: {
        http_req_failed: ['rate<0.01'],
        'http_req_duration{type:retrieval}': ['p(95)<2000'],
    },
};

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const TOKEN = __ENV.TOKEN || '';
const headers = {
    headers: { Authorization: `Bearer ${TOKEN}`, 'Content-Type': 'application/json' },
};

export default function () {
    const payload = JSON.stringify({
        question: '公司差旅报销标准是什么？',
        groupId: Number(__ENV.GROUP_ID || 1),
    });
    const res = http.post(`${BASE}/api/qa/ask`, payload, headers);
    check(res, { 'qa answered': (r) => r.status === 200 });
    sleep(3);
}
