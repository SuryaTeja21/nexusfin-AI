## 📋 Pull Request Description
<!-- Provide a brief summary of the changes introduced by this PR -->

### Related Jira / GitHub Issue
- Fixes # (issue)
- User Story: # (story)

---

## 🛠️ Type of Change
- [ ] 🚀 New Feature (non-breaking change which adds functionality)
- [ ] 🐛 Bug Fix (non-breaking change which fixes an issue)
- [ ] 🔒 Security / Compliance Enhancement
- [ ] ⚡ Performance Optimization
- [ ] 📝 Documentation / ADR Update
- [ ] 🔄 Refactoring / Tech Debt

---

## 🏦 FinTech & Double-Entry Invariant Checklist
- [ ] Strict double-entry invariant holds ($\sum \text{Debits} = \sum \text{Credits}$).
- [ ] Row locks are deterministically sorted to prevent deadlock conditions.
- [ ] Idempotency key deduplication is strictly enforced.
- [ ] Database schema changes include idempotent Flyway migration scripts (`V*`).
- [ ] Distributed tracing header (`X-Correlation-Id`) is propagated across downstream calls.

---

## 🧪 Definition of Done (DoD) Verification
- [ ] All unit and integration tests pass locally.
- [ ] JaCoCo code coverage meets or exceeds threshold ($\ge 80\%$).
- [ ] No secrets or sensitive keys are committed.
- [ ] Docker Compose orchestration verified.
