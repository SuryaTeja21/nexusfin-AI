import http from 'k6/http';
import { check, fail } from 'k6';

export const GATEWAY_URL = __ENV.GATEWAY_URL || 'http://localhost:8080';
export const KEYCLOAK_URL = __ENV.KEYCLOAK_URL || 'http://localhost:8083';
export const CLIENT_ID = __ENV.CLIENT_ID || 'nexusfin-gateway';
export const ADMIN_USERNAME = __ENV.ADMIN_USERNAME || 'admin1';
export const ADMIN_PASSWORD = __ENV.ADMIN_PASSWORD || 'admin_password';

export function mintAdminToken() {
  const res = http.post(
    `${KEYCLOAK_URL}/realms/nexusfin/protocol/openid-connect/token`,
    {
      client_id: CLIENT_ID,
      grant_type: 'password',
      username: ADMIN_USERNAME,
      password: ADMIN_PASSWORD,
    },
    {
      headers: {
        Host: 'keycloak:8080',
        'Content-Type': 'application/x-www-form-urlencoded',
      },
      tags: { name: 'keycloak_token' },
    }
  );
  if (res.status !== 200) {
    fail(`Keycloak token acquisition failed (${res.status}): ${res.body}`);
  }
  return res.json('access_token');
}

export function ensureAccount(token, accountNumber, accountName, initialBalance) {
  const authHeaders = {
    Authorization: `Bearer ${token}`,
    'Content-Type': 'application/json',
  };

  const getRes = http.get(
    `${GATEWAY_URL}/api/v1/accounts/number/${encodeURIComponent(accountNumber)}`,
    { headers: authHeaders, tags: { name: 'setup_get_account' } }
  );
  if (getRes.status === 200) {
    return getRes.json();
  }

  const createRes = http.post(
    `${GATEWAY_URL}/api/v1/accounts`,
    JSON.stringify({
      accountNumber,
      accountName,
      accountType: 'LIABILITY',
      currency: 'USD',
      initialBalance,
    }),
    { headers: authHeaders, tags: { name: 'setup_create_account' } }
  );

  if (createRes.status !== 201 && createRes.status !== 200) {
    fail(`Failed to provision account ${accountNumber} (${createRes.status}): ${createRes.body}`);
  }
  return createRes.json();
}

export function postTransfer(token, sourceAccountNumber, destinationAccountNumber, amount, idempotencyKey, description, tagName = 'post_transfer') {
  const res = http.post(
    `${GATEWAY_URL}/api/v1/ledger/transfers`,
    JSON.stringify({
      sourceAccountNumber,
      destinationAccountNumber,
      amount,
      currency: 'USD',
      idempotencyKey,
      description,
    }),
    {
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
        'X-Correlation-Id': `k6-${idempotencyKey}`,
      },
      tags: { name: tagName },
    }
  );

  check(res, {
    'transfer status is 201': (r) => r.status === 201,
  });
  return res;
}

export function readAccount(token, accountNumber, tagName = 'get_account') {
  const res = http.get(
    `${GATEWAY_URL}/api/v1/accounts/number/${encodeURIComponent(accountNumber)}`,
    {
      headers: {
        Authorization: `Bearer ${token}`,
        'X-Correlation-Id': `k6-read-${__VU}-${__ITER}`,
      },
      tags: { name: tagName },
    }
  );

  check(res, {
    'read status is 200': (r) => r.status === 200,
  });
  return res;
}
