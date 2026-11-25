import { mintAdminToken, ensureAccount, postTransfer } from './lib/common.js';

const VUS = Number(__ENV.K6_VUS || 20);
const DURATION = __ENV.K6_DURATION || '20s';

export const options = {
  scenarios: {
    duplicate_idempotency_replay: {
      executor: 'constant-vus',
      vus: VUS,
      duration: DURATION,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{name:post_transfer_replay}': ['p(95)<300', 'p(99)<600'],
  },
};

export function setup() {
  const token = mintAdminToken();
  const accA = 'ACC-PERF-REPLAY-A';
  const accB = 'ACC-PERF-REPLAY-B';
  ensureAccount(token, accA, 'Perf Replay Account A', 10000000.00);
  ensureAccount(token, accB, 'Perf Replay Account B', 10000000.00);

  // Pre-commit one transfer synchronously so all VUs replay an already-committed idempotency key
  const sharedIdempotencyKey = `k6-replay-seed-${Date.now()}`;
  postTransfer(
    token,
    accA,
    accB,
    10.00,
    sharedIdempotencyKey,
    'k6 scenario (d): initial transfer for replay benchmark',
    'setup_seed_transfer'
  );

  return { token, accA, accB, sharedIdempotencyKey };
}

export default function (data) {
  postTransfer(
    data.token,
    data.accA,
    data.accB,
    10.00,
    data.sharedIdempotencyKey,
    'k6 scenario (d): duplicate idempotency replay',
    'post_transfer_replay'
  );
}
