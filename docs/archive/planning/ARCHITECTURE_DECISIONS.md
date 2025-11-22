# Key Architectural Decisions

## Decision 1: Query-Wide Timeout (Not Per-Term)
- **Status:** DECIDED
- **Rationale:** Matches Cassandra query timeout semantics
- **Date:** 2025-11-17

## Decision 2: Dual Eviction (LRU + Idle-Time)
- **Status:** DECIDED
- **Rationale:** Short-term performance + long-term memory cleanup
- **Date:** 2025-11-17