# Maintenance job target policy check

Maintenance jobs (upsert and delete jobs) write to one fixed physical target index. A rolling index
would move writes to a new partition, so the target must not be managed by a rollover policy. When a
job is created, `JobTargetAcceptanceCheck.check(targetIndex)` reads the ISM policy applied to the
target **once** via ISM Explain (`GET _plugins/_ism/explain/<targetIndex>?show_policy=true`) and looks
for a `rollover` action in every state of the policy, including states not yet reached.

The check does not run at startup; it is a building block for the job creation API.

## Outcomes

| Applied policy                                                                 | Outcome                 | Result                                                            |
|--------------------------------------------------------------------------------|-------------------------|-------------------------------------------------------------------|
| Contains a rollover action                                                     | `ROLLOVER_IN_POLICY`    | Refused: `not-allowed` with target and reason                     |
| Has no rollover action                                                         | `NO_ROLLOVER_IN_POLICY` | Allowed                                                           |
| None reported (no policy attached, or attached and not yet initialized)        | `NO_POLICY_APPLIED`     | Allowed                                                           |
| Cannot be read (missing permission, ISM metadata unavailable, policy id known but definition not yet applied) | `POLICY_UNDETERMINED`   | Allowed, without claiming that non-rolling operation was verified |

A policy that ISM is still initializing is reported as `NO_POLICY_APPLIED` or, once its id is known
but before its definition is applied, as `POLICY_UNDETERMINED`. Both are allowed.

`JobTargetAcceptance.requireAllowed()` throws `JobTargetNotAllowedException` (error code
`not-allowed`) for a refusal; no job or command may be accepted then.

## Errors

Anything that is not a policy result is thrown as a public `IndexWriterException` subtype from
`ch.admin.bit.jeap.opensearch.indexwriter.domain.maintenance.acceptance`. Each has a `getErrorCode()` and
`getTargetIndex()`, so callers map it without parsing the message:

| Exception                              | Error code            | Retryable | Cause                                                             | Suggested API response |
|----------------------------------------|-----------------------|-----------|-------------------------------------------------------------------|------------------------|
| `JobTargetNotAllowedException`         | `not-allowed`         | no        | Applied policy has a rollover action (see above).                 | 409 or 422             |
| `JobTargetNameInvalidException`        | `target-name-invalid` | no        | Name does not match `[a-z0-9][a-z0-9._-]{0,254}`.                  | 400                    |
| `JobTargetNotFoundException`           | `target-not-found`    | no        | The target index does not exist.                                  | 404                    |
| `JobTargetNotConcreteException`        | `target-not-concrete` | no        | The target is an alias; `getResolvedIndices()` lists the indices behind it. | 422          |
| `JobTargetPolicyCheckFailedException`  | `policy-check-failed` | yes, no for 401 | OpenSearch unavailable, throttled (429) or 5xx, or the credentials were rejected (401). | 503, 500 for 401 |

The target must be one physical index (e.g. `jme_decree_document_v1-000001`), not an alias.
An alias such as `jme_decree_document_v1_write` is rejected with `JobTargetNotConcreteException` instead of
being checked, also when ISM Explain cannot report the target.

## Permissions

The check needs the cluster permission `cluster:admin/opendistro/ism/managedindex/explain`, see
[OpenSearch permissions](opensearch-permissions.md). Without it, every check reports
`POLICY_UNDETERMINED` and allows the target: rolling targets are then **not** refused. The only signals
are a `WARN` log per check and the `policy_undetermined` value of the
`jeap.opensearch.indexwriter.maintenance.policy.check` metric ([Metrics](metrics.md)). Grant the
permission when upgrading and **alert on a nonzero rate of the `policy_undetermined` outcome** —
without that alert, a missing permission degrades this check to a silent no-op instead of being
noticed. A 403 from ISM Explain is additionally logged at `ERROR`, naming the missing permission.

With `jeap.opensearch.indexwriter.maintenance.policy-check.verify-permission-on-startup: true`, the service also
probes ISM Explain once at startup (`ApplicationReadyEvent`) and logs an `ERROR` if the call is denied (401 or 403),
so a missing permission shows up when the service starts, not only on the first check. Enable it in services that use
the check; it is off by default so that services without maintenance jobs are not alerted. The probe never prevents
the service from starting; any other answer, including an unreachable cluster, is not an error.

A 401 (rejected credentials) is not a policy result but a configuration error: the check throws a non-retryable
`JobTargetPolicyCheckFailedException` instead of allowing the job.

When ISM Explain does not report the target, the check sends `GET /<targetIndex>?filter_path=*.settings.index.uuid` to tell a missing
target from an unreadable policy, and to reject an alias that resolves to other indices. This needs the index
permission `indices:admin/get`; without it, a missing target or an alias is allowed as `POLICY_UNDETERMINED`
instead of being refused.

## Accepted limitations

This is a best-effort, point-in-time check with deliberately accepted limitations:

- Only the currently applied policy counts. Existing partitions, rollover history and data-stream
  layouts are not inspected; a target that rolled over in the past is allowed if its current policy
  has no rollover action.
- ISM attaches a policy asynchronously. Until it has applied the policy, the check allows the target.
- The check is not repeated during task execution or recovery. A policy change after acceptance
  neither cancels nor fails an accepted job; tasks write to the accepted target and ordinary write
  errors remain visible.

## Related

- [OpenSearch permissions](opensearch-permissions.md)
- [Metrics](metrics.md)
- [Write operations](write-operations.md)
- [jeap-opensearch-index-writer-service](../README.md)
