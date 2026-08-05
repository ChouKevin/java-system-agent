---
example_only: true
operation_id: semantic-entrypoint-inspection
repo_ids:
  - java-system-agent
entry_point_ids:
  - java-system-agent/api-route-lookup/EP-001
  - java-system-agent/jdtls-maintenance/EP-001
  - java-system-agent/orders-consumer/EP-001
status: draft
---

# Semantic entry-point inspection

This example operation groups an API route lookup, scheduled JDT workspace maintenance,
and a message-consumer shape. Knowledge locates candidates; the current codebase determines
actual behavior, including whether an anchor is still present and what it does.

The `orders-consumer` entry point is an `illustrative_test_fixture` and explicitly non-runtime;
it demonstrates a message-consumer record shape only and is not evidence of a deployed listener.
