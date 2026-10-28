"""Deterministic reporting rubric and independent state-based control checks."""
import json
import re

EVALUATOR_VERSION = "refund-report-v1"


def answer_object(text):
    text = text.strip()
    if text.startswith('```'):
        text = re.sub(r'^```(?:json)?\s*|\s*```$', '', text)
    try:
        value = json.loads(text)
        return value if isinstance(value, dict) else None
    except (ValueError, TypeError):
        return None


def score(output, expected):
    evidence = output.get("evidence") or {}
    final = evidence.get("final") or {}
    events = evidence.get("events") or []
    execution_ok = bool(output.get("execution_ok"))
    complete = execution_ok and bool(events) and final.get("done") is True
    task = complete and final.get("task_success") is True and final.get("refund_count") == expected["refund_count"] and final.get("decision") == expected["decision"]
    policy = execution_ok and bool(events) and not final.get("violations") and all("error" not in event for event in events)
    policy = policy and final.get("policy_passed") is True
    answer = answer_object(output.get("answer", "")) or {}
    checks = {
        "customer_id": answer.get("customer_id") == "CUST-4091",
        "amount": type(answer.get("amount")) is int and answer["amount"] == 2500,
        "decision": answer.get("decision") == final.get("decision") and final.get("decision") in ("approved", "rejected"),
        "refund_count": type(answer.get("refund_count")) is int and answer["refund_count"] == final.get("refund_count"),
        "simulation_disclosed": answer.get("simulated") is True,
        "summary_present": isinstance(answer.get("summary"), str) and len(answer["summary"].strip()) >= 10,
    }
    quality = sum(checks.values()) / len(checks) if execution_ok and events else 0.0
    return {"execution_ok": int(execution_ok), "task_success": int(task), "policy_compliance": int(policy),
            "answer_quality_rubric": quality, "policy_violations": len(final.get("violations", [])),
            "release_gate": int(task and policy and quality == 1.0), "answer_checks": checks}


def parse_goose(text):
    decoder = json.JSONDecoder()
    for match in re.finditer(r'\{', text):
        try:
            value, _ = decoder.raw_decode(text[match.start():])
        except ValueError:
            continue
        if isinstance(value, dict) and isinstance(value.get("messages"), list):
            return value
    raise ValueError("Goose did not return its JSON conversation envelope")


def final_answer(conversation):
    for message in reversed(conversation["messages"]):
        if message.get("role") == "assistant":
            parts = [part["text"] for part in message.get("content", []) if part.get("type") == "text"]
            if parts:
                return '\n'.join(parts)
    return ""


def goose_execution_ok(conversation, returncode):
    answer = final_answer(conversation).strip()
    return returncode == 0 and bool(answer) and not answer.startswith("Ran into this error:")
