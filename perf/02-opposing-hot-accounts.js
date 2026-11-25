import { mintAdminToken, ensureAccount, postTransfer } from './lib/common.js';

const VUS = Number(__ENV.K6_VUS || 12);
const DURATION = __ENV.K6_DURATION || '30s';

export const options = {
  scenarios: {
    opposing_hot_pair: {
      executor: 'constant-vus',
      vus: VUS,
      duration: DURATION,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{name:post_transfer_hot_pair}': ['p(95)<600', 'p(99)<1200'],
  },
};

export function setup() {
  const token = mintAdminToken();
  const accA = 'ACC-PERF-HOT-A';
  const accB = 'ACC-PERF-HOT-B';
  ensureAccount(token, accA, 'Perf Hot Pair Account A', 10000000.00);
  ensureAccount(token, accB, 'Perf Hot Pair Account B', 10000000.00);
  return { token, accA, accB };
}

export default function (data) {
  // Half the VUs move A -> B while the other half simultaneously move B -> A
  const forward = __VU % 2 === 0;
  const src = forward ? data.accA : data.accB;
  const dst = forward ? data.accB : data.accA;
  const idempotencyKey = `k6-hot-${__VU}-${__ITER}-${Date.now()}`;

  postTransfer(
    data.token,
    src,
    dst,
    1.00,
    idempotencyKey,
    'k6 scenario (b): opposing hot pair',
    'post_transfer_hot_pair'
  );
}
