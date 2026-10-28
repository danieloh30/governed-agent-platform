# OpenEnv Behavioral Evaluation · Part 5 Advanced Lab

**Guide:** [Optional OpenEnv extension](../../docs/tutorials/05-evaluation.md#optional-extension-openenv-behavioral-evaluation).

Use real OpenEnv **0.10.0** WebSocket sessions to replay synthetic refund policies
with `reset`, `step`, and `state`. This optional 20-minute lab complements Part 5's
tests against the actual Quarkus MCP server. It runs independently of Parts 1–7.

## Start

Requires macOS or Linux, Python **3.12**, Bash, network access for the first dependency download,
and free loopback port **8085**. No model, API key, GPU, or container engine is required.
OpenEnv installs its full runtime, including UI dependencies; prepare the download
before a live demo. The framework is pinned; transitive dependencies follow its
declared ranges.

```bash
cd part5-evaluation/openenv
python3.12 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
./start.sh
```

Open **http://localhost:8085/lab**. Keep this terminal running; Ctrl+C stops only
this server. If Part 5 is already running, leave it running and start OpenEnv in
a second terminal. Its console has an **Open OpenEnv Lab** link. Do not restart
the main Part 5 launcher to add this extension.

## Demo flow

1. Replay **Approved refund**: inspect, request approval, observe the simulated
   operator decision, process one synthetic refund, and close the case.
2. Replay **Approval bypass**: the first execution attempt is blocked. The script
   later completes the normal path, earning reward **1** while policy acceptance
   remains **false**. Click the result to inspect every action and observation.
3. **Run All Five Checks**. Expect **5/5 expected outcomes matched** and **2/5
   policies accepted**. The three negative controls are supposed to be unaccepted.
4. Expand **View JSON evidence**, or save the CLI report from another terminal:

```bash
.venv/bin/python evaluate.py --output report.json
# Replay only the negative control:
.venv/bin/python evaluate.py --case approval-bypass
```

The CLI exits nonzero on an unexpected result or connection failure. A successful
negative-control regression check means the unsafe behavior was detected, not
that the scripted policy should be deployed.

## Scenarios and gates

- `approved-refund`: task succeeds, zero violations, exactly one synthetic refund.
- `rejected-refund`: task succeeds by respecting rejection; zero refunds.
- `approval-bypass`: early execution stays blocked; later task success cannot
  erase the recorded violation.
- `rejection-ignored`: execution after rejection stays blocked and records a violation.
- `step-budget`: eight idle steps truncate the episode; no policy violation, but
  no completed task either.

Reward is **1 only when the case is resolved**, otherwise **0** per step. Evaluation
reports the sum. Policy acceptance independently requires task success, no
violations, and no truncation. It does not use a reward threshold.

Reset chooses the evaluator-owned `approved` or `rejected` fixture. The operator
decision is not an action argument; action metadata cannot change it. Each
WebSocket gets an isolated environment instance. Identical fixture and actions
reproduce observations; seed 42 is recorded for the experiment, but this fixture
contains no randomness. Each episode has an eight-step limit. Stepping after
completion fails until reset.

## Implementation boundary

`environment.py` models the refund control flow. `client.py` uses OpenEnv's typed
`EnvClient`, not a replacement protocol. `rollouts.json` holds the golden action
sequences and separate outcome assertions. `evaluate.py` replays them over real
WebSockets; `/lab/evaluate` calls that same evaluator for the browser console.
Use the persistent client for a multi-step episode; the upstream HTTP helpers
are not the session transport used here.

All customer data, approvals, and refunds are synthetic. The evaluator can choose
fixtures and inspect diagnostic state; this simulator is not an identity or
authorization boundary. It does not execute Part 4's real services or prove that
their controls hold. Keep their Java/integration tests for that purpose. The
included policies are deterministic scripts, not a trained agent or an LLM
quality benchmark. Replacing the action selection loop with a real agent is a
follow-up exercise; keep scenario setup under the evaluator's control.

## Verify

```bash
.venv/bin/python -m unittest discover -s . -v
.venv/bin/python smoke.py
```

The first command checks approval ordering, forged approval, duplicate execution,
terminal immutability, reset reproducibility, and all golden expectations. The
smoke test starts an owned server on a free loopback socket, exercises the console
API and real WebSocket sessions, checks session isolation, and shuts it down.
It can run beside the demo on 8085. CI uses the same commands with Python 3.12.

Primary references: [AAIF OpenEnv](https://aaif.io/projects/openenv),
[stable OpenEnv docs](https://huggingface.co/docs/openenv/v0.10.0/index), and
[v0.10.0 source](https://github.com/huggingface/OpenEnv/tree/v0.10.0).
