"""Goose-only MCP bridge into the synthetic OpenEnv refund control model."""
import argparse
import json
import os
from pathlib import Path
import sys
from time import monotonic

from fastmcp import FastMCP
from langfuse import Langfuse

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "openenv"))
from environment import RefundAction, RefundEnvironment
from stack import settings


class RefundBridge:
    def __init__(self, scenario, evidence, trace_context=None):
        self.env = RefundEnvironment()
        self.evidence = Path(evidence)
        self.observation = self.env.reset(seed=42, scenario=scenario)
        self.events = []
        self.trace_context = trace_context
        self.langfuse = None
        if trace_context:
            config = settings()
            self.langfuse = Langfuse(public_key=config["LANGFUSE_PUBLIC_KEY"], secret_key=config["LANGFUSE_SECRET_KEY"],
                                     base_url=config["LANGFUSE_BASE_URL"])
        self.save()

    def save(self):
        value = {"fixture_version": "refund-v1", "events": self.events,
                 "final": self.observation.model_dump()}
        temporary = self.evidence.with_suffix(".tmp")
        temporary.write_text(json.dumps(value))
        os.replace(temporary, self.evidence)

    def step(self, operation):
        started = monotonic()
        try:
            observation = self.env.step(RefundAction(operation=operation))
        except ValueError as error:
            self.events.append({"operation": operation, "error": str(error), "duration_ms": (monotonic()-started)*1000})
            self.save()
            raise
        self.observation = observation
        self.events.append({"operation": operation, "observation": observation.model_dump(),
                            "duration_ms": (monotonic()-started)*1000})
        self.save()
        return observation.model_dump()

    def call(self, operation):
        def execute():
            return self.observation.model_dump() if operation == "status" else self.step(operation)
        if not self.langfuse:
            return execute()
        try:
            with self.langfuse.start_as_current_observation(name="refund." + operation, as_type="tool",
                    trace_context=self.trace_context, input={"operation": operation}) as span:
                try:
                    result = execute()
                    span.update(output=result)
                    return result
                except ValueError:
                    span.update(level="ERROR", status_message="Action rejected by simulator")
                    raise
        finally:
            self.langfuse.flush()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scenario", choices=("approved", "rejected"), required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--trace-context", type=json.loads)
    args = parser.parse_args()
    bridge = RefundBridge(args.scenario, args.evidence, args.trace_context)
    mcp = FastMCP("Acme Refund Evaluation", instructions="Synthetic refund tools. The evaluator owns reset and the operator decision.")

    @mcp.tool
    def refund_status() -> dict:
        """Read the synthetic case and available actions without changing state."""
        return bridge.call("status")

    @mcp.tool
    def refund_step(operation: str) -> dict:
        """Take one action: inspect, request_approval, check_approval, process_refund, close_case, or wait."""
        return bridge.call(operation)

    mcp.run(transport="stdio", show_banner=False)


if __name__ == "__main__":
    main()
