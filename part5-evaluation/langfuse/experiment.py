"""Compare two real Goose prompt variants and publish independent scores to local Langfuse."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import signal
import subprocess
import sys
import tempfile
from time import monotonic

from langfuse import Evaluation, Langfuse

from scoring import EVALUATOR_VERSION, final_answer, goose_execution_ok, parse_goose, score
from stack import settings

HERE = Path(__file__).resolve().parent
DATA = json.loads((HERE / "dataset.json").read_text())


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True).encode()).hexdigest()


def goose_environment(context_tokens):
    return {**os.environ, "GOOSE_MODE": "auto", "GOOSE_INPUT_LIMIT": str(context_tokens),
            "GOOSE_CONTEXT_LIMIT": str(context_tokens)}


def run_goose(case, prompt, provider, model, timeout, context=None, context_tokens=8192):
    with tempfile.TemporaryDirectory(prefix="acme-goose-eval-") as directory:
        evidence = Path(directory) / "evidence.json"
        command = [sys.executable, str(HERE / "bridge.py"), "--scenario", case["scenario"], "--evidence", str(evidence)]
        if context:
            command += ["--trace-context", json.dumps(context)]
        args = [os.environ.get("GOOSE_BIN", "goose"), "run", "--no-profile", "--no-session", "--quiet",
                "--output-format", "json", "--provider", provider, "--model", model,
                "--max-turns", "14", "--max-tool-repetitions", "2",
                "--with-extension", shlex.join(command), "--system", prompt, "--text", case["question"]]
        started = monotonic()
        process = subprocess.Popen(args, cwd=directory, env=goose_environment(context_tokens),
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, start_new_session=True)
        failure = None
        try:
            stdout, _stderr = process.communicate(timeout=timeout)
            try:
                conversation = parse_goose(stdout)
                answer = final_answer(conversation)
                # Goose may exit zero even when its provider failed.
                execution_ok = goose_execution_ok(conversation, process.returncode)
            except ValueError:
                conversation, answer, execution_ok = {}, "", False
            if not execution_ok:
                failure = "Goose execution/provider failure; inspect local Goose logs"
        except subprocess.TimeoutExpired:
            conversation, answer, execution_ok = {}, "", False
            failure = "Goose exceeded the per-case time budget"
        finally:
            # Also close any MCP child left behind by an exited Goose parent.
            try:
                os.killpg(process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
        audit = json.loads(evidence.read_text()) if evidence.exists() else None
        return {"answer": answer, "execution_ok": execution_ok, "error": failure,
                "evidence": audit, "duration_ms": (monotonic()-started)*1000,
                "goose_usage": conversation.get("metadata", {})}


def evaluator(*, output, expected_output, **kwargs):
    values = score(output or {}, expected_output)
    return [Evaluation(name=name, value=value, comment="Deterministic reporting rubric; review semantics separately" if name == "answer_quality_rubric" else "Measured from captured simulator evidence")
            for name, value in values.items() if name != "answer_checks"]


def compare_reports(reports, case_ids):
    """Missing cases or scores never pass the release gate."""
    required = set(case_ids)
    baseline, candidate = reports
    maps = [{row["case_id"]: row for row in report["items"]} for report in reports]
    complete = all(set(items) == required and len(items) == len(report["items"])
                   for items, report in zip(maps, reports))
    regressions = [case for case in required if maps[0].get(case, {}).get("scores", {}).get("release_gate") == 1
                   and maps[1].get(case, {}).get("scores", {}).get("release_gate") != 1]
    accepted = complete and all(row["scores"].get("release_gate") == 1 for row in candidate["items"])
    return {"complete": complete, "candidate_passed": accepted, "newly_failing_cases": sorted(regressions)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--provider", default="ollama")
    parser.add_argument("--model", default="qwen3:4b")
    parser.add_argument("--case", action="append", choices=[case["id"] for case in DATA])
    parser.add_argument("--timeout", type=int, default=180)
    parser.add_argument("--context-tokens", type=int, default=8192, help="Per-process context budget (1024–32768); Ollama allocation uses this too")
    parser.add_argument("--output", type=Path, default=HERE.parent / "target" / "langfuse-comparison.json")
    parser.add_argument("--no-think", action="store_true", help="Append Qwen3 /no_think to both variants; use only with a model that supports it")
    parser.add_argument("--require-candidate", action="store_true", help="Exit nonzero unless every candidate case passes all gates")
    args = parser.parse_args()
    if not 1024 <= args.context_tokens <= 32768:
        parser.error("--context-tokens must be between 1024 and 32768")
    if args.timeout <= 0:
        parser.error("--timeout must be positive")
    if not shutil.which(os.environ.get("GOOSE_BIN", "goose")):
        parser.error("Install Goose 1.46.0+ and configure a model provider")
    config = settings()
    client = Langfuse(public_key=config["LANGFUSE_PUBLIC_KEY"], secret_key=config["LANGFUSE_SECRET_KEY"], base_url=config["LANGFUSE_BASE_URL"])
    if not client.auth_check():
        raise RuntimeError("Langfuse authentication failed; start the local stack first")
    selected = [case for case in DATA if not args.case or case["id"] in args.case]
    version = subprocess.check_output([os.environ.get("GOOSE_BIN", "goose"), "--version"], text=True).strip()
    commit = subprocess.check_output(["git", "-C", str(HERE), "rev-parse", "HEAD"], text=True).strip()
    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    dataset_hash = digest(selected)
    reports = []
    try:
        for variant in ("baseline", "candidate"):
            prompt = (HERE / "prompts" / (variant + ".txt")).read_text()
            metadata = {"dataset_hash": dataset_hash, "evaluator_version": EVALUATOR_VERSION,
                        "prompt_hash": digest(prompt), "variant": variant, "commit": commit,
                        "provider": args.provider, "model": args.model, "goose_version": version, "thinking_hint": str(args.no_think), "context_tokens": str(args.context_tokens),
                        "fixture_version": "refund-v1", "source_hash": digest([(p.name, p.read_text()) for p in [HERE/"experiment.py", HERE/"bridge.py", HERE/"scoring.py", HERE.parent/"openenv"/"environment.py"]])}
            items = [{"input": {"case_id": case["id"], "question": case["question"]},
                      "expected_output": {"decision": case["scenario"], "refund_count": case["expected_refund_count"]},
                      "metadata": {"case_id": case["id"]}} for case in selected]
            cases = {case["id"]: case for case in selected}

            def task(*, item, **kwargs):
                case = dict(cases[item["input"]["case_id"]])
                if args.no_think:
                    case["question"] += "\n/no_think"
                print(f"Running {variant} / {case['id']} with {args.provider}/{args.model}", flush=True)
                with client.start_as_current_observation(name="goose.refund-investigation", as_type="agent",
                        input={"question": case["question"], "system_prompt": prompt}, metadata=metadata) as span:
                    result = run_goose(case, prompt, args.provider, args.model, args.timeout,
                                       {"trace_id": span.trace_id, "parent_span_id": span.id}, context_tokens=args.context_tokens)
                    span.update(output=result["answer"], metadata={**metadata, "goose_usage": result["goose_usage"], "duration_ms": result["duration_ms"]})
                    return result

            result = client.run_experiment(name="Acme Goose Refund Evaluation", run_name=f"{run_id}-{args.model.replace(':', '_')}-{variant}",
                    data=items, task=task, evaluators=[evaluator], max_concurrency=1, metadata=metadata)
            rows = [{"case_id": item.item["input"]["case_id"], "output": item.output,
                     "scores": {evaluation.name: evaluation.value for evaluation in item.evaluations},
                     "trace_id": item.trace_id} for item in result.item_results]
            reports.append({"variant": variant, "experiment_id": result.experiment_id,
                            "experiment_url": result.experiment_url, "metadata": metadata, "items": rows})
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.with_suffix(".partial.json").write_text(json.dumps({"runs": reports}, indent=2) + "\n")
            print(f"{variant}: {result.experiment_url}", flush=True)
        comparison = compare_reports(reports, [case["id"] for case in selected])
        args.output.write_text(json.dumps({"runs": reports, "comparison": comparison}, indent=2) + "\n")
        print(json.dumps(comparison))
        print(f"Evidence: {args.output}")
        # A run error fails even without a strict candidate quality gate.
        errors = any(not row["scores"].get("execution_ok") for report in reports for row in report["items"])
        return 1 if errors or not comparison["complete"] or (args.require_candidate and not comparison["candidate_passed"]) else 0
    finally:
        client.flush()
        client.shutdown()


if __name__ == "__main__":
    raise SystemExit(main())
