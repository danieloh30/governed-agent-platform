# Part 7: Governed Code Execution with OpenSandbox

**Long-form guide:** [Part 7 tutorial](../docs/tutorials/07-sandbox-execution.md).

Sofia investigates a synthetic settlement discrepancy using a fixed Python
script in a temporary OpenSandbox. A Quarkus MCP tool and the same-origin
console share one Java service for allowlist validation, bounded execution,
evidence capture, and remote cleanup. No LLM or earlier service is required.

## Start

Requires Java 25+, Maven 3.9+, Bash, Python 3.10+, curl, and a running local
Docker or Podman engine with bridge networking and its compatible API socket.

```bash
cd part7-opensandbox
./sandbox.sh up
./start-all.sh
```

Open **http://127.0.0.1:8084/**. OpenSandbox listens on **127.0.0.1:8090**.
Set `CONTAINER_ENGINE=docker` or `CONTAINER_ENGINE=podman` when both are installed.
The launcher mounts the engine socket into the lifecycle server; use a private
development engine. Transient execution ports 42000–43000 are published on the
engine host so the server can probe them; protect them from untrusted ingress.

Server/SDK: **1.2.0**; execd: **v1.1.0**; egress: **v1.1.7**; workload:
**python:3.12.12-slim-bookworm**. These are release tags, not image digests.

## Exercises

```bash
curl --fail-with-body -sS http://127.0.0.1:8084/api/runs \
  -H 'Content-Type: application/json' \
  -d '{"scenario":"reconcile"}' | python3 -m json.tool
```

- `reconcile`: three discrepancies, settlement-minus-ledger delta −4500 cents.
- `egress-check`: hostname and direct-IP probes should fail; inspect stdout.
- `timeout`: a 30-second sleep under a 2-second execution deadline should fail.
- `script-error`: a deliberate exit code 7 should fail.

Every created sandbox should report cleanup `DELETED`, including failed runs.
`DELETE_FAILED` and `CREATE_UNCONFIRMED` need operator inspection. No arbitrary
commands, data uploads, host mounts, or payment actions are accepted.

MCP endpoint: **http://127.0.0.1:8084/mcp**. Tool: **reconcileSettlements** with
one `scenario` argument. See `goose-extension-config.yaml` and the tutorial.

## Verify

From the repository root:

```bash
mvn -pl part7-opensandbox -am test
# With both services running:
python3 part7-opensandbox/smoke.py --evidence-dir /tmp/part7-evidence
```

Unit tests exercise cleanup and failure paths without a runtime. The smoke
check uses real OpenSandbox, invokes reconciliation through MCP, and requires
HTTP 404 when querying deleted sandbox IDs. Two failed network probes are not
comprehensive proof of isolation. Results are returned evidence, not durable
audit records. The local setup does not authenticate users or move funds.

## Stop and recover

Stop the Quarkus launcher with Ctrl+C, then run `./sandbox.sh down`. It deletes
only workloads marked `tutorial=governed-agent-platform-part7` before removing
the server. If the control plane is unavailable, it refuses to remove the
server: inspect `./sandbox.sh logs`, restore access, and retry `python3 cleanup.py`.

If the server failed before becoming healthy and has no workloads, inspect and
remove only its container with your engine's `rm governed-part7-opensandbox`,
then rerun `./sandbox.sh up`. Never disable egress enforcement to work around
bridge or sidecar readiness errors; verify the engine socket and host gateway.
