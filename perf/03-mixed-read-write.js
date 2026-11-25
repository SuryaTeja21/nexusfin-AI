import { mintAdminToken, ensureAccount, postTransfer, readAccount } from './lib/common.js';

const VUS = Number(__ENV.K6_VUS || 16);
const DURATION = __ENV.K6_DURATION || '30s';

export const options = {
  scenarios: {
    mixed_read_write: {
      executor: 'constant-vus',
      vus: VUS,
      duration: DURATION,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{name:mixed_read}': ['p(95)<300'],
    'http_req_duration{name:mixed_write}': ['p(95)<500'],
  },
};

export function setup() {
  const token = mintAdminToken();
  const accounts = [];
  for (let i = 1; i <= 4; i++) {
    const accNo = `ACC-PERF-MIX-${i}`;
    ensureAccount(token, accNo, `Perf Mixed Workload Account ${i}`, 10000000.00);
    accounts.push(accNo);
  }
  return { token, accounts };
}

export default function (data) {
  const idx = (__VU + __ITER) % data.accounts.length;
  // 80% reads / 20% writes
  if (__ITER % 5 === 0) {
    const dstIdx = (idx + 1) % data.accounts.length;
    const idempotencyKey = `k6-mix-${__VU}-${__ITER}-${Date.now()}`;
    postTransfer(
      data.token,
      data.accounts[idx],
      data.accounts[dstIdx],
      2.50,
      idempotencyKey,
      'k6 scenario (c): mixed write',
      'mixed_write'
    );
  } else {
    readAccount(data.token, data.accounts[idx], 'mixed_read');
  }
}
