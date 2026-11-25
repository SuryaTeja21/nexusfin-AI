import { mintAdminToken, ensureAccount, postTransfer } from './lib/common.js';

const VUS = Number(__ENV.K6_VUS || 12);
const DURATION = __ENV.K6_DURATION || '30s';

export const options = {
  scenarios: {
    unique_transfers: {
      executor: 'constant-vus',
      vus: VUS,
      duration: DURATION,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{name:post_transfer_unique}': ['p(95)<500', 'p(99)<1000'],
  },
};

export function setup() {
  const token = mintAdminToken();
  const accounts = [];
  for (let i = 1; i <= 8; i++) {
    const accNo = `ACC-PERF-POOL-${i}`;
    ensureAccount(token, accNo, `Perf Pool Account ${i}`, 10000000.00);
    accounts.push(accNo);
  }
  return { token, accounts };
}

export default function (data) {
  const srcIdx = (__VU + __ITER) % data.accounts.length;
  const dstIdx = (srcIdx + 1) % data.accounts.length;
  const idempotencyKey = `k6-uniq-${__VU}-${__ITER}-${Date.now()}`;

  postTransfer(
    data.token,
    data.accounts[srcIdx],
    data.accounts[dstIdx],
    1.25,
    idempotencyKey,
    'k6 scenario (a): unique transfer',
    'post_transfer_unique'
  );
}
