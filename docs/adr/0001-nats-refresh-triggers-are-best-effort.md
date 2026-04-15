# ADR 0001: NATS Refresh Triggers Are Best Effort

## Status

Accepted

## Context

`plugin-config` consumes NATS events on `config.{app}.{env}.changed` to reduce reload latency.
The server publishes those events outside the database transaction that commits config changes.

That means:

- A committed config change may exist even when no NATS event is observed.
- A NATS event may be delayed, duplicated, or lost without violating the system contract.
- The NATS payload is not authoritative state and may drift independently from the source-of-truth
  snapshot model.

The client already treats NATS as a trigger only: the listener reads the event for logging and then
reconciles through `GetSnapshotIfNewer`.

## Decision

The correctness anchor for config propagation is the gRPC snapshot reconcile path, not the NATS
event stream.

NATS is used only as a best-effort hint to trigger an earlier refresh. Consumers must not derive
config state, ordering, or durability guarantees from the NATS subject or payload alone.

## Consequences

- Missing or malformed NATS events must not break correctness; they only affect propagation
  latency.
- Client implementations should keep event handling minimal and always reconcile through
  `GetSnapshotIfNewer`.
- Server and client changes should preserve this contract and avoid coupling correctness to NATS
  delivery guarantees.
- Future documentation and architecture discussions should describe NATS as a latency optimization,
  not as the source of truth.
