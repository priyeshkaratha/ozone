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

# Secure (Kerberos) Ozone cluster

This Docker Compose environment starts a **Kerberos-enabled** single-node Ozone
cluster: one SCM, one OM, one datanode, Recon, S3 Gateway, HttpFS, a Hadoop KMS,
and a KDC. Security is on (`ozone.security.enabled=true`): all RPC uses SASL/
Kerberos, block/container tokens and gRPC TLS are enabled, and every web
endpoint is protected with SPNEGO.

For a Highly Available variant (3 SCM, 3 OM, Ranger), see
[`../ozonesecure-ha`](../ozonesecure-ha).

## Prerequisites

- `docker` and `docker-compose` (Compose v2, i.e. `docker compose`, also works).
- A built Ozone distribution. This directory is a copy inside the distribution
  (`ozone-*-SNAPSHOT/compose/ozonesecure`); the services mount the surrounding
  distribution at `/opt/hadoop`. Build one with:

  ```
  mvn -Pdist -DskipTests package
  ```

  then `cd hadoop-ozone/dist/target/ozone-*-SNAPSHOT/compose/ozonesecure`.
- No local Kerberos client or `krb5.conf` is required on the host — everything
  runs inside the containers.

## How Kerberos is initialized

There is nothing to set up by hand:

1. The `kdc` container runs [`../common/init-kdc.sh`](../common/init-kdc.sh),
   which creates every service and test principal (`scm`, `om`, `dn`, `recon`,
   `s3g`, `httpfs`, `HTTP/*`, plus `testuser` / `testuser2`) and exports them to
   keytabs under the `_keytabs` volume shared by all containers
   (`/etc/security/keytabs`). It then starts `krb5kdc`.
2. Every Ozone service `depends_on` the `kdc` becoming **healthy**, so services
   only start once the KDC is listening and the keytabs exist. The KMS waits for
   the same condition.
3. Each service reads its principal/keytab from
   [`docker-config`](./docker-config) and logs in on startup.

The realm is `EXAMPLE.COM` and the KDC host is `kdc` (see
[`krb5.conf`](./krb5.conf)).

## Start the cluster

```
docker-compose up -d
```

Wait for SCM to leave safe mode (a datanode must register first):

```
docker-compose exec scm bash -c \
  'kinit -k HTTP/scm@EXAMPLE.COM -t /etc/security/keytabs/HTTP.keytab && ozone admin safemode wait -t 120'
```

Scale datanodes if you need more:

```
docker-compose up -d --scale datanode=3
```

## Get a Kerberos ticket

Client commands need a ticket. Every container has the shared keytabs mounted,
so `kinit` inside any container works. `testuser` is an Ozone administrator:

```
docker-compose exec scm bash
kinit -k -t /etc/security/keytabs/testuser.keytab testuser/scm@EXAMPLE.COM
ozone sh volume create /vol1
ozone fs -mkdir -p ofs://om/vol1/bucket1/dir1
```

Available test identities (from `init-kdc.sh`):

| Principal            | Keytab               | Role                                    |
|----------------------|----------------------|-----------------------------------------|
| `testuser/<host>`    | `testuser.keytab`    | Ozone admin (`ozone.administrators`)    |
| `testuser2/<host>`   | `testuser2.keytab`   | Recon admin (`ozone.recon.administrators`) |
| `scm`,`om`,`dn`, ... | `<service>.keytab`   | service identities                      |

## Access Recon (including the UI)

In secure mode the Recon web server enforces SPNEGO **and** authorizes requests
against `ozone.administrators` / `ozone.recon.administrators`. From the command
line this just needs a ticket:

```
docker-compose exec scm bash
kinit -k -t /etc/security/keytabs/testuser.keytab testuser/scm@EXAMPLE.COM
curl --negotiate -u : http://recon:9888/api/v1/clusterState
```

A **browser**, however, cannot do SPNEGO without a host Kerberos ticket and
per-browser Negotiate configuration, so `http://localhost:9888` returns `401`
out of the box. To browse the UI without that setup, start the cluster with the
`recon-ui` overlay:

```
docker-compose -f docker-compose.yaml -f recon-ui.yaml up -d
```

Then open <http://localhost:9888>. The overlay:

- switches **only Recon's** HTTP auth to `simple` (all other services stay on
  Kerberos), and
- puts a small nginx reverse proxy ([`recon-ui`](./recon-ui.yaml), config in
  [`../common/recon-ui.nginx.conf`](../common/recon-ui.nginx.conf)) in front of
  it that injects an admin identity (`user.name=testuser`) into every request.

Recon still enforces authorization — a request without that identity gets `401`
and a non-admin gets `403` — so this is a developer convenience, not a way to
disable security. The overlay is **not** used by the acceptance tests, so the
tested cluster keeps Recon on Kerberos/SPNEGO.

## Other web UIs

OM (`9874`), SCM (`9876`), S3G (`9878`) and Recon (`9888` without the overlay)
are all SPNEGO-protected. Access them with `curl --negotiate -u :` after
`kinit`, or configure your browser's `Negotiate` trusted URIs for
`localhost`/`127.0.0.1` and `kinit` on the host.

## Run the acceptance tests

```
./test.sh
```

`test.sh` uses `docker-compose.yaml` only (no overlay), starts the cluster,
`kinit`s automatically, creates an encryption key in the KMS, and runs the
Robot Framework smoke tests under [`../../smoketest`](../../smoketest),
including the `recon` and `spnego` suites.

## Troubleshooting

- **`docker-compose up` fails with `port is already allocated`** — another
  cluster is running. Stop it (`docker-compose down` in that directory) or run
  this one with the ports remapped.
- **Recon UI shows `401` in the browser** — you started without the overlay.
  Use `-f docker-compose.yaml -f recon-ui.yaml` (see above).
- **Recon UI shows `403` / empty panels** — the injected `user.name` is not an
  admin. Confirm `testuser` is in `ozone.administrators` in `docker-config`.
- **Recon capacity / pending-deletion shows `-1` or "collection failed"** —
  Recon collects these by negotiating SPNEGO with each datanode's `/jmx`
  endpoint as `HTTP/<datanode-hostname>`. The datanode HTTP server uses
  `hdds.datanode.http.auth.kerberos.principal=HTTP/_HOST` from `dn.keytab`, and
  `init-kdc.sh` must hold a matching `HTTP/<hostname>` for every datanode name.
  If you scale beyond 3 datanodes, add the extra
  `HTTP/ozonesecure-datanode-<N>.ozonesecure_default` principals there and
  reinitialize with `docker-compose down -v`.
- **`GSSException: No valid credentials provided` / `Server not found in
  Kerberos database`** — the client has no ticket or it expired; run `kinit`
  again inside the container. Check `docker-compose logs kdc`.
- **SCM never leaves safe mode** — no datanode registered. Check
  `docker-compose logs datanode`; scale up with `--scale datanode=3`.
- **Clock skew errors** — Kerberos is time-sensitive; make sure the Docker VM
  clock is correct.
- **Stale state after config changes** — `docker-compose down -v` removes the
  volumes (metadata and keytabs) so the next start reinitializes cleanly.

## Stop the cluster

```
docker-compose down        # stop and remove containers
docker-compose down -v     # also remove volumes (metadata, keytabs)
```
