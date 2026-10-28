"""Typed client using OpenEnv's real persistent WebSocket transport."""

from openenv.core.client_types import StepResult
from openenv.core.env_client import EnvClient

from environment import RefundAction, RefundObservation, RefundState


class RefundClient(EnvClient[RefundAction, RefundObservation, RefundState]):
    def _step_payload(self, action):
        return action.model_dump()

    def _parse_result(self, payload):
        observation = RefundObservation.model_validate({
            **payload["observation"], "done": payload["done"], "reward": payload.get("reward"),
        })
        return StepResult(observation, payload.get("reward"), payload["done"], payload.get("metadata"))

    def _parse_state(self, payload):
        return RefundState.model_validate(payload)
