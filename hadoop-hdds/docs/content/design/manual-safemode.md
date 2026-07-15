---
title: Manual Safe Mode for SCM
summary: Allow an administrator to put SCM into safe mode at runtime (for all SCMs or a single SCM) for maintenance, without restarting SCM.
date: 2026-07-15
jira: HDDS-15866
status: draft
author: Priyesh Karatha
---
<!--
  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License. See accompanying LICENSE file.
-->

## Summary

Let an administrator put SCM into safe mode on demand  for all SCMs or for a
single SCM. So that write operations are frozen during maintenance, without
restarting SCM. Reads continue to work. This mirrors HDFS NameNode
`dfsadmin -safemode enter`.

## Problem statement

Today SCM enters safe mode only during startup. The `SCMSafeModeManager`
holds SCM in safe mode
until a set of exit rules (datanodes registered, enough container replicas
reported, healthy pipelines, etc.) are satisfied, then SCM automatically leaves
safe mode. Once SCM is out of safe mode there is **no way to re-enter safe mode
without restarting SCM**.

Operators may need to move safemode to hold write operations sometime.

HDFS solves the equivalent problem for the NameNode with
`hdfs dfsadmin -safemode enter` (and `-fs hdfs://<host>:<port> -safemode enter`
to target a single NameNode in an HA deployment). This proposal brings the same
capability to SCM.

The key design principle: **Manual Safe Mode is not a new kind of safe mode
from a client's perspective.** SCM internally has two independent *reasons* for
being in safe mode that is *startup* and *manual*  but OM, the SDKs, the S3 Gateway
and end users only ever observe a single boolean "SCM is in safe mode". No
client-visible protocol, exception, or retry behavior changes.

## Non-goals

* **No change to startup safe mode.** The existing startup rules, their
  evaluation, and automatic exit continue to work exactly as today. No
  regression is acceptable.
* **No new client-visible safe mode type.** OM and SDK clients must require no
  changes. The only place the startup-vs-manual distinction is visible is the
  administrative CLI.
* **Not a cluster-wide replicated state.** Manual Safe Mode is deliberately
  per-SCM local state (see *Alternatives*). It is not persisted to disk and is
  not replicated through the SCM Ratis ring.
* **Not a replacement for decommissioning, upgrade prepare, or maintenance
  mode of datanodes.** This is purely an SCM-level write freeze.

## Background: how SCM safe mode works today

`SCMSafeModeManager` keeps a single status
(`INITIAL → PRE_CHECKS_PASSED → OUT_OF_SAFE_MODE`). Everything that must be
blocked in safe mode checks `StorageContainerManager.getScmContext().isInSafeMode()`,
which reads a cached copy of that status. For example container allocation and
pipeline creation throw `SCMException(SAFE_MODE_EXCEPTION)` while in safe mode,
and background services (ReplicationManager, SCMBlockDeletingService,
BackgroundPipelineCreator) pause while `SCMContext.isInSafeMode()` is true.

The administrative surface today:

* `ozone admin safemode status` — is SCM in safe mode?
* `ozone admin safemode exit` — force SCM out of safe mode.
* `ozone admin safemode wait` — block until SCM leaves safe mode.

`exit` is already an *all-SCMs* operation: the SCM client fans the request out
to every SCM in the service, and each SCM applies it locally. `status` can
target a specific SCM with `--scm host:port`.

## Technical description

### Safe mode as two reasons

SCM keeps two independent flags:

```
startupSafeMode   // driven by the existing exit rules
manualSafeMode    // set/cleared only by an administrator
```

The *effective* safe mode state — the only thing clients ever observe — is:

```
isInSafeMode = startupSafeMode || manualSafeMode
```

`manualSafeMode` is a single in-memory `AtomicBoolean` in `SCMSafeModeManager`.
`getInSafeMode()` returns the OR of the two. Because every existing caller of
`isInSafeMode()` already reads through this method (and through the cached
`SCMContext.isInSafeMode()`), **the write-blocking logic is reused unchanged** —
Manual Safe Mode blocks exactly the same operations as startup safe mode, with
the same exceptions and the same client retries.

`SCMContext` mirrors the manual flag alongside its cached startup status so its
`isInSafeMode()` also returns the effective state, without disturbing the
`preCheckComplete` semantics of the startup status enum.

### Automatic exit is neutralized, not disabled

While Manual Safe Mode is active, the startup exit rules continue to be
evaluated and refreshed so that metrics and observability remain accurate.
However, they cannot take SCM out of safe mode: the single fan-out point that
publishes safe mode changes (`emitSafeModeStatus()`) recomputes the effective
state via the OR above. So even if all startup rules pass while manual mode is
on, SCM remains in safe mode and background services stay paused. When Manual
Safe Mode is later cleared, the effective state drops and services resume. This
keeps rule evaluation intact for observability while guaranteeing Manual Safe
Mode never auto-exits.

### Per-node state: all SCMs vs a single SCM

Manual Safe Mode is **per-SCM local state**, exactly like HDFS NameNode safe
mode. This gives operators two behaviors, mirroring HDFS:

* **All SCMs** (`ozone admin safemode enter`): the SCM client fans the request
  out to every SCM in the service — reusing the existing mechanism that
  `ozone admin safemode exit` already uses — and each SCM sets its own local
  flag.
* **A single SCM** (`ozone admin safemode enter --scm <host:port>`): the client
  routes the request to just that SCM, which sets only its own flag.

A replicated, cluster-wide flag was considered and rejected precisely because
it cannot express "put one SCM in safe mode" (see *Alternatives*).

### Lifecycle

```
                 admin: safemode enter [--scm host]
   ┌─────────────────────────────────────────────────────────────┐
   │                                                             ▼
Normal ── startup ──► (rules pass) ──► Normal ── enter ──► Manual Safe Mode
   ▲                                                             │
   │                admin: safemode exit [--scm host]            │
   └─────────────────────────────────────────────────────────────┘

While in Manual Safe Mode:
  - reads served, writes rejected (SAFE_MODE_EXCEPTION), services paused
  - startup rules still evaluated (metrics) but cannot auto-exit
  - only 'safemode exit' clears it
```

### Administrative CLI

| Command                                                     | Behavior                                                                                                                                                                                                       |
| ----------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `ozone admin safemode enter`                              | Put**all** SCMs into manual safe mode.                                                                                                                                                                   |
| `ozone admin safemode enter --scm <host:port>`            | Put a**single** SCM into manual safe mode.                                                                                                                                                               |
| `ozone admin safemode exit`                               | Force**all** SCMs out of safe mode (clears manual and startup). Existing behavior, extended to clear manual.                                                                                             |
| `ozone admin safemode exit --scm <host:port>`             | Force a**single** SCM out of safe mode.                                                                                                                                                                  |
| `ozone admin safemode status [--scm <host:port>] [--all]` | Report whether SCM is in safe mode, and — for administrators only — whether the reason is`STARTUP` or `MANUAL`. Per-node reporting already exists.                                                       |
| `ozone admin safemode wait`                               | Unchanged for startup safe mode. When the SCM is in**manual** safe mode it returns immediately with a message that manual intervention is required, instead of waiting until the (configurable) timeout. |

### Wire protocol and backward compatibility

The changes to `ScmAdminProtocol.proto` are additive and backward compatible:

* A new `EnterSafeMode` RPC (`EnterSafeModeRequestProto` /
  `EnterSafeModeResponseProto`). Older servers simply do not implement it;
  older clients never call it.
* An optional `SafeModeReasonProto { SAFE_MODE_REASON_NONE, STARTUP, MANUAL }`
  field added to the existing `InSafeModeResponseProto`. The required
  `inSafeMode` boolean is untouched, so `inSafeMode()` behaves identically for
  OM, the SDKs and the S3 Gateway. Only the admin CLI reads the new field.

`EnterSafeMode` and the existing `ForceExitSafeMode` are treated as *node
targetable* admin commands: with no `--scm` option the client fans them out to
all SCMs; with `--scm host:port` the client routes them to that one SCM. No new
Ratis request types, no metadata/RocksDB schema changes, and no upgrade or
finalization impact.

### SCM HA behavior

Because Manual Safe Mode is per-node local state:

* Entering "all" reaches every SCM that is reachable at the time of the command
  (client fan-out), matching HDFS `-safemode enter`.
* A leader change does not clear it on nodes that already have it set; each SCM
  independently keeps its flag.
* It is **not persisted**: an SCM that restarts comes back up governed only by
  the usual startup safe mode, and re-entering manual mode on it (if desired) is
  an explicit admin action. This matches HDFS NameNode safe mode, which is also
  lost on NN restart.

### Startup Safe Mode vs Manual Safe Mode

| Aspect                                 | Startup Safe Mode                      | Manual Safe Mode                             |
| -------------------------------------- | -------------------------------------- | -------------------------------------------- |
| Entered automatically                  | Yes (on SCM start)                     | No (admin`safemode enter`)                 |
| Can be entered by admin                | No                                     | Yes                                          |
| Auto-exits when rules pass             | Yes                                    | No                                           |
| Requires manual exit                   | Optional (force-exit allowed)          | Required (`safemode exit`)                 |
| Exit rules evaluated / metrics updated | Yes                                    | Yes (but cannot trigger exit)                |
| Blocks writes                          | Yes                                    | Yes                                          |
| Allows reads                           | Yes                                    | Yes                                          |
| Client-visible behavior                | `SAFE_MODE_EXCEPTION`, retries       | Identical                                    |
| CLI status reason                      | `STARTUP`                            | `MANUAL`                                   |
| Scope                                  | Per SCM (each node runs its own rules) | Per SCM (all-SCM fan-out or single`--scm`) |

### Operations while Manual Safe Mode is active

Manual Safe Mode reuses the existing safe mode gating, so the set of allowed
and rejected operations is identical to startup safe mode:

| Component | Operation                                                 | Allowed |
| --------- | --------------------------------------------------------- | ------- |
| OM        | Read / list keys, buckets, volumes                        | Yes     |
| OM        | Create / delete / commit key (needs SCM block allocation) | No      |
| SCM       | List containers / pipelines, read metrics                 | Yes     |
| SCM       | Allocate container, create pipeline                       | No      |
| Recon     | Read metrics / views                                      | Yes     |
| CLI       | `safemode status`, container/pipeline list              | Yes     |
| CLI       | `safemode exit`                                         | Yes     |

## Alternatives

**Cluster-wide flag replicated through SCM Ratis.** An earlier iteration made
enter/exit a `@Replicate` SCM state-manager operation so the flag was identical
on every SCM and survived leader failover. This was rejected for two reasons:
(1) a replicated, global flag is by definition cluster-wide and therefore
*cannot* express "put a single SCM in safe mode", which is an explicit
requirement (and the HDFS behavior operators expect); and (2) it adds a new
Ratis request type and state-machine handler for state that is transient
operational intent, when the existing per-node client fan-out already provides
the "all SCMs" behavior for `safemode exit`. The per-node local model reuses
proven mechanisms and supports both "all" and "single" cleanly.

**Persisting manual safe mode across restarts.** We chose not to persist the
flag. HDFS NameNode safe mode is likewise not persisted across an NN restart,
and persisting would pull in RocksDB schema and upgrade/finalization concerns
for what is a short-lived maintenance state. If a durable variant is needed
later it can be added as a follow-up.

**A separate `manualSafeMode`/`MaintenanceMode` service independent of
`SCMSafeModeManager`.** Rejected to avoid duplicating the write-blocking and
service-pause plumbing. Modeling manual mode as a second *reason* inside the
existing manager reuses every existing `isInSafeMode()` call site unchanged.

## Implementation plan

The change is moderate and does not require a feature branch:

* `SCMSafeModeManager`: add the `manualSafeMode` flag, OR it into
  `getInSafeMode()`, add local enter/exit, and make the status-publishing path
  compute the effective state (so background services pause/resume correctly).
* `SCMContext`: mirror the manual flag into its cached `isInSafeMode()`.
* `StorageContainerManager` / `SCMClientProtocolServer`: an admin-gated
  `enterSafeMode()`; extend `exit` to clear the manual flag; expose the safe
  mode reason.
* `ScmAdminProtocol.proto` and the client/server translators: the additive
  `EnterSafeMode` RPC and the optional reason field.
* SCM client routing: allow `EnterSafeMode`/`ForceExitSafeMode` to target a
  single SCM when `--scm` is given; otherwise fan out to all.
* CLI: new `safemode enter` subcommand; `--scm` targeting for `enter`/`exit`;
  `status` reports the reason; `wait` returns immediately under manual mode.

### Testing

* Unit tests for `SCMSafeModeManager`: manual enter keeps SCM in safe mode even
  after startup rules pass; auto-exit is neutralized while manual is active;
  exit clears it; reason reporting.
* An SCM HA integration test asserting per-node semantics: entering manual mode
  on one SCM does not affect the others, it never auto-exits, and exit clears it.
* Regression: all existing startup safe mode tests must pass unchanged.

## References

* HDFS analogue: [https://docs.cloudera.com/runtime/7.3.2/fault-tolerance/topics/cr-turning-safemode-ha.html](https://docs.cloudera.com/runtime/7.3.2/fault-tolerance/topics/cr-turning-safemode-ha.html)
* JIRA: HDDS-15866
* Existing SCM safe mode: `hadoop-hdds/server-scm/.../scm/safemode/SCMSafeModeManager.java`
