#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "=== Running k6 Suite: (a) Unique Idempotency Transfers ==="
k6 run "$SCRIPT_DIR/01-transfers-unique.js"

echo "=== Running k6 Suite: (b) Opposing-Pair Hot Accounts ==="
k6 run "$SCRIPT_DIR/02-opposing-hot-accounts.js"

echo "=== Running k6 Suite: (c) Mixed Read/Write (80/20) ==="
k6 run "$SCRIPT_DIR/03-mixed-read-write.js"

echo "=== Running k6 Suite: (d) Duplicate Idempotency-Key Replay ==="
k6 run "$SCRIPT_DIR/04-duplicate-idempotency-replay.js"
