"""Loopback OpenEnv simulation server and companion evaluation console."""

from pathlib import Path

from fastapi import HTTPException, Request
from fastapi.responses import FileResponse
from openenv.core.env_server.http_server import create_fastapi_app
from pydantic import BaseModel, ConfigDict

from environment import RefundAction, RefundEnvironment, RefundObservation, RefundState
from evaluate import CASES, evaluate

HERE = Path(__file__).resolve().parent
app = create_fastapi_app(
    RefundEnvironment, RefundAction, RefundObservation, state_cls=RefundState,
    env_name="acme_refund", max_concurrent_envs=8, mode="simulation",
)


class EvaluationRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    case_id: str | None = None


@app.get("/lab")
async def console():
    return FileResponse(HERE / "index.html")


@app.get("/lab/cases")
async def cases():
    return [{"id": case["id"], "description": case["description"]} for case in CASES]


@app.post("/lab/evaluate")
async def run(request: Request, selection: EvaluationRequest):
    if selection.case_id is not None and selection.case_id not in {case["id"] for case in CASES}:
        raise HTTPException(400, "Unknown rollout")
    # Use the actual listening socket's port, not an untrusted Host header.
    port = request.scope["server"][1]
    try:
        return await evaluate(f"http://127.0.0.1:{port}", selection.case_id)
    except Exception as error:
        raise HTTPException(503, "OpenEnv evaluation failed; inspect the server terminal") from error
