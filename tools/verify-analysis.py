"""Verify the installed Ghidra MCP transport without attaching a running game."""
import asyncio
from datetime import timedelta
import json
from pathlib import Path
import sys
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

ROOT = Path(__file__).resolve().parents[1]
PACK = ROOT / "CE-Ghidra-MCP-Pack"


async def main():
    params = StdioServerParameters(command=sys.executable,
        args=[str(PACK / "ghidra_brawl/launch.py"), "--no-lazy"], cwd=str(PACK / "ghidra_brawl"))
    with (ROOT / "build/ghidra-mcp.stderr.log").open("w") as log:
        async with stdio_client(params, errlog=log) as (reader, writer):
            async with ClientSession(reader, writer, read_timeout_seconds=timedelta(seconds=90)) as client:
                await client.initialize()
                result = await client.list_tools()
                names = {tool.name for tool in result.tools}
                assert {"check_connection", "get_project_info", "decompile_function", "search_tools"} <= names
                health = await client.call_tool("check_connection", {})
                assert not health.isError
                report = {"mcp_initialized": True, "tools": len(names), "health": [c.text for c in health.content if hasattr(c,"text")],
                          "target_attached": False, "ce_workbench": "unavailable: required packaged modules missing"}
                (ROOT / "build/analysis-verification.json").write_text(json.dumps(report, indent=2))
                print(json.dumps(report, indent=2))


if __name__ == "__main__":
    asyncio.run(main())
