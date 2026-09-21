import http from 'k6/http';
import { check } from 'k6';

const DROP_ID = 'add-your-id-here';
const API_KEY = 'add-your-key-here';

export const options = {
    vus: 200,          // 200 virtual concurrent users
    iterations: 10000, // 10,000 total requests across those users
};

export default function () {
    const res = http.post(
        `http://localhost:8080/v1/drops/${DROP_ID}/claims`,
        JSON.stringify({ customerReference: `user-${__VU}-${__ITER}` }),
        {
            headers: {
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${API_KEY}`,
            },
        }
    );

    check(res, {
        'status is 200': (r) => r.status === 200,
    });
}