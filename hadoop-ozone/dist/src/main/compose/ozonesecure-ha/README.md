<!---
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

# Secure (Kerberos) Ozone HA cluster

Highly available, Kerberos-enabled Ozone cluster: **3 SCMs** (`scm1.org`,
`scm2.org`, `scm3.org`), **3 OMs** (`om1`, `om2`, `om3`), datanodes, Recon,
S3 Gateway, HttpFS, a Hadoop KMS and a KDC, on a static-IP network
(`172.25.0.0/24`). An Apache Ranger stack is available via
[`ranger.yaml`](./ranger.yaml).

This shares the same Kerberos setup, test identities and conventions as the
single-node [`../ozonesecure`](../ozonesecure) environment — **read its
[README](../ozonesecure/README.md) first**. Only the HA-specific differences are
covered here.

## Start the cluster

```
docker-compose up -d
```

HA services wait for the primary SCM before starting (`WAITFOR`), so startup
takes longer than the single-node cluster. `OM_SERVICE_ID` is `omservice` and
`SCM` service id is `scmservice`.

## Access Recon (including the UI)

Identical to the single-node cluster. For a browsable UI without per-user
SPNEGO setup, start with the overlay:

```
docker-compose -f docker-compose.yaml -f recon-ui.yaml up -d
```

then open <http://localhost:9888>. See
[`../ozonesecure/README.md`](../ozonesecure/README.md#access-recon-including-the-ui)
for how the overlay works and its security caveats.

## Run the acceptance tests

```
./test.sh
```

Uses `docker-compose.yaml` only (no overlay); Recon stays on Kerberos/SPNEGO for
the tested cluster.

## Troubleshooting

See the [single-node troubleshooting](../ozonesecure/README.md#troubleshooting)
section. HA-specific notes:

- **An OM/SCM will not start** — it is waiting on the primary SCM
  (`scm3.org:9894`). Check `docker-compose logs scm3.org` first.
- **Recon cannot reach an OM/SCM** — the HA services use fixed IPs and
  `extra_hosts`; make sure you did not change the `172.25.0.0/24` subnet.
