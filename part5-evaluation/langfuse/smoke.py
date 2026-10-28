"""Exercise the real MCP stdio bridge with no model, Langfuse server, or API keys."""
import asyncio
import json
from pathlib import Path
import sys
import tempfile

from fastmcp import Client
from fastmcp.client.transports import StdioTransport

HERE = Path(__file__).resolve().parent


async def main():
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory)/'evidence.json'
        transport = StdioTransport(command=sys.executable, args=[str(HERE/'bridge.py'), '--scenario','rejected','--evidence',str(path)])
        async with Client(transport) as client:
            tools = await client.list_tools()
            assert {tool.name for tool in tools} == {'refund_status','refund_step'}
            for operation in ['inspect','request_approval','check_approval','process_refund','close_case']:
                result = await client.call_tool('refund_step', {'operation':operation})
                assert not result.is_error
            evidence = json.loads(path.read_text())
            assert evidence['final']['task_success'] and evidence['final']['refund_count'] == 0
            assert len(evidence['final']['violations']) == 1
            assert not evidence['final']['policy_passed']
        print('PASS real MCP discovery, rejected refund, captured violation, and terminal evidence')


if __name__=='__main__':asyncio.run(main())
