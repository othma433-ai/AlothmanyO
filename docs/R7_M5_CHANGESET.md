# R7-M5 Changeset — Evidence-Based New-Only + High-Water Checkpoint

R7-M5 replaces the legacy anchor-only New-only boundary with a versioned evidence-based checkpoint while preserving backward compatibility with existing `lastNewOnlyBoundary` values.

## Added
- `NewOnlyExtractionStrategy`
- v2 high-water checkpoint containing only signatures/hashes:
  - message anchors
  - viewport signature
  - timestamp-context hashes
  - normalized-URL fingerprints
- explicit stop reasons:
  - `PRIOR_CHECKPOINT_MATCH`
  - `UNREAD_BOUNDARY`
  - `HISTORICAL_NO_PROGRESS`
- legacy semicolon-anchor checkpoint decoding
- `R7-M5` pure JVM tests and compatibility contract
- M5 gates in GitHub Actions

## Runtime behavior
- Each visible viewport is durably persisted before New-only boundary evaluation.
- A v2 prior checkpoint requires at least the required anchor overlap plus independent contextual evidence (viewport/timestamp/URL).
- An explicit unread divider is accepted as a boundary.
- Historical terminal consensus remains bounded at 3 no-progress confirmations.
- The next high-water checkpoint is captured from the newest viewport but is not committed until extraction completion and final conversation re-verification.
- No raw message text, timestamp string, or URL is persisted in the high-water token.

## Compatibility
- Existing legacy anchor tokens remain readable.
- Room stays at v4; no schema change was required.
- Deep extraction behavior and immutable queue semantics from M4 remain unchanged.
