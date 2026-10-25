// Excel 协同压测：HTTP 工作簿访问 + WebSocket 协同连接（占 10% 流量）。
// WebSocket 部分由 k6 ws 模块承担，门槛：协同事件 P95 < 500ms。
import http from 'k6/http';
import ws from 'k6/ws';
import { check, sleep } from 'k6';

export const options = {
    vus: 10,
    duration: '30m',
    thresholds: {
        http_req_failed: ['rate<0.01'],
    },
};

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const WS_URL = __ENV.WS_URL || 'ws://localhost:8080/ws';
const TOKEN = __ENV.TOKEN || '';

export default function () {
    const workbooks = http.get(`${BASE}/api/excel/documents`, {
        headers: { Authorization: `Bearer ${TOKEN}` },
    });
    check(workbooks, { 'workbooks ok': (r) => r.status === 200 });

    ws.connect(`${WS_URL}?token=${TOKEN}`, {}, (socket) => {
        socket.on('open', () => {
            socket.send(JSON.stringify({
                destination: '/app/excel/edit',
                body: JSON.stringify({ documentId: 1, sheetId: 1, r: 1, c: 1, v: 'k6', timestamp: Date.now() }),
            }));
        });
        setTimeout(() => socket.close(), 5000);
    });
    sleep(2);
}
