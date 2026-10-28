# Langfuse + Real Goose Experiments · Part 5 Optional Extension

Compare baseline and candidate Goose instructions on the **same synthetic refund
cases**, with real model-selected MCP calls. Langfuse stores the experiments,
agent/tool traces, outputs, scores, and comparison history. The original Java
MCP regression suites and scripted OpenEnv rollouts remain independently runnable.

## Prerequisites

- macOS or Linux, Python 3.12, Goose **1.46.0+**, Git, and Docker Compose or Podman
  with a Compose provider. Start the container engine first.
- Allocate approximately **4 CPUs and 16 GiB RAM** to the local container engine.
  Allow time and disk space for initial image/dependency/model downloads.
- Free loopback port **3000**. Dependency services have no host-published ports.
- A working Goose model provider. Defaults are local **Ollama + qwen3:4b**;
  the model download is approximately 2.5 GB. Its tool calling and final JSON formatting can vary. Model quality is measured,
  not assumed. Provider/model overrides apply only to the experiment process.

Pinned components: Langfuse server/worker **4.71.0**, Python SDK **5.0.4**,
OpenEnv **0.10.0**. SDK v5 needs Langfuse v4.59.0 or newer. The Compose stack is
adapted from the official v4.71.0 example; MinIO is pinned by image digest.

## Start the local instance

From the repository root:

```bash
cd part5-evaluation/langfuse
python3.12 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
.venv/bin/python stack.py up
# In another terminal, if Ollama is not already running:
ollama serve
# Download once:
ollama pull qwen3:4b
```

If Part 5 is already running, leave it running and use another terminal for
this extension. No Part 1, Part 4, or OpenEnv server on port 8085 is required.
The bridge imports the same OpenEnv refund model directly; each Goose run owns
one fresh instance with no external business operations.

Open **http://localhost:3000**. Retrieve the generated local UI login with:

```bash
.venv/bin/python stack.py credentials
```

Use **demo@acme.invalid** and that generated password. The initialized project is
**Goose Refund Experiments** (`acme-goose`). Credentials are stored with mode
0600 under `$XDG_STATE_HOME/governed-agent-platform/langfuse/config.json`, or
`~/.local/state/governed-agent-platform/langfuse/config.json` by default. They
stay outside the repository and the Part 5 static-server directory. Scripts
read them directly; the SPA does not receive secret keys.

Set `CONTAINER_ENGINE=docker` or `CONTAINER_ENGINE=podman` to select the engine
when both are installed. Use `stack.py status` to inspect only this stack.

## Run the live comparison

```bash
# First demo: one case through both real Goose prompt variants.
.venv/bin/python experiment.py --case approved-refund

# Complete dataset: approval, rejection, and an unverified approval claim.
.venv/bin/python experiment.py
```

Both variants use the same provider/model and dataset. `prompts/baseline.txt`
asks Goose to resolve the case using the available tools. `prompts/candidate.txt`
adds explicit investigation, approval observation, rejection, and reporting
instructions. The candidate is a hypothesis to test; it is not guaranteed to win.
Each case gets a new `goose run --no-profile --no-session` process with only the
synthetic refund MCP extension. Auto mode applies only to this dedicated run.
No default Goose profile, Developer extension, or global settings are changed.

The bridge exposes `refund_status` and `refund_step`. Reset and operator decisions
belong to the evaluator, so Goose cannot select its own approval fixture through
a tool. Blocked or invalid actions remain in captured evidence. Each episode has
an eight-step limit; Goose has a 14-turn limit and a default 180-second deadline.

To test another provider/model already configured in Goose:

```bash
.venv/bin/python experiment.py --provider ollama --model YOUR_TOOL_CAPABLE_MODEL
# Optional for Qwen3: request non-thinking mode in both variants.
.venv/bin/python experiment.py --no-think
```

The runner caps context at **8192 tokens per process**, including Ollama's actual
allocation via `GOOSE_INPUT_LIMIT`. This avoids inheriting a very large model
context window. `--context-tokens` permits 1024–32768 tokens; both variants use the
same limit and record it in metadata. Local inference still uses the GPU and can
increase fan noise. Release idle model memory afterward with `ollama stop qwen3:4b`;
`ollama ps` lists active runners. These settings do not change global Goose or
Ollama configuration.

The model must support tool calling. A provider error can produce Goose exit code
zero; the runner also inspects its structured conversation and captured evidence.
It never treats missing evidence as a passing policy check. Hosted providers use
your existing Goose credentials and normal provider billing.

## Inspect the evidence in Langfuse

1. Open the initialized project, then **Experiments**.
2. Select the two timestamped runs ending in `-baseline` and `-candidate`.
3. Open their comparison and use the baseline run as the reference.
4. Compare the **same case IDs**, then open an item trace. Inspect the
   `goose.refund-investigation` agent span and its actual `refund.*` tool spans.
5. Review separate scores: `execution_ok`, `task_success`, `policy_compliance`,
   `answer_quality_rubric`, `policy_violations`, and `release_gate`.
6. Inspect the answer against tool observations. Record human review scores and
   comments in Langfuse for semantic correctness and clarity.

The tool spans are emitted when the MCP bridge executes the actions, using the
Goose run's parent trace context. They are not reconstructed from a scripted
trajectory. This integration captures the agent prompt, final answer, and tool
activity; it does not instrument individual provider model calls or invent token
costs. Goose's aggregate usage is recorded as metadata when available.

`answer_quality_rubric` measures **report completeness and factual field
agreement**, not general language quality. The required final JSON includes
`customer_id`, `amount`, observed `decision`, observed `refund_count`,
`simulated:true`, and a nonempty summary. The score is the fraction of six
checks satisfied. It can miss a misleading summary; human review remains needed.
An LLM-as-judge evaluator in Langfuse is a follow-up requiring a configured judge
model and a calibrated rubric. It does not replace deterministic policy checks.

## Reports and CI gate

The runner saves `part5-evaluation/target/langfuse-comparison.json` only after
both variants complete; interrupted checkpoints use `.partial.json`. The completed
report contains experiment links, case IDs, model/provider, Goose version, commit, dataset/prompt hashes,
evaluator version, traces, outputs, and separate scores. A complete execution
returns zero even if model quality is poor. Use the explicit release gate in CI:

```bash
.venv/bin/python experiment.py --require-candidate
```

This requires every candidate case to pass task success, policy compliance, and
all six reporting checks. Missing cases, missing scores, provider failures, and
timeouts fail. The comparison also lists newly failing baseline cases; the
baseline is not automatically approved or replaced. Repeat trials with the same
inputs and evaluator before drawing conclusions about stochastic models.

After a live run, verify that Langfuse actually persisted the item scores and
parent-linked tool spans (the check waits briefly for ingestion):

```bash
.venv/bin/python verify.py
```

The checked-in CI workflow runs provider-free scoring and real MCP bridge checks.
Live model/stack runs are opt-in; they are not required on every PR. Locally:

```bash
.venv/bin/python -m unittest discover -s . -v
.venv/bin/python smoke.py
```

## Stop and restart

```bash
.venv/bin/python stack.py down
```

This stops/removes only the `governed-langfuse` Compose services and network.
Database/object-store volumes and private credentials remain for the next
`stack.py up`. Other tutorial containers are unaffected.

References: [Langfuse experiments](https://langfuse.com/docs/evaluation/experiments/experiments-via-sdk),
[comparison guidance](https://langfuse.com/docs/evaluation/experiments/compare-experiments),
[compatibility](https://langfuse.com/docs/compatibility),
[self-hosting](https://langfuse.com/self-hosting/deployment/docker-compose), and
[headless initialization](https://langfuse.com/self-hosting/administration/headless-initialization).
