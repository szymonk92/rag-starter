"""MCP server over a small ops knowledge base.
Read-only lookups, plus one side effect (create_ticket) that asks the human through MCP elicitation (a resolver) and is idempotent.
Run by a client over stdio: python3 ops_server.py"""
import json
from pathlib import Path
from typing import Annotated

from pydantic import BaseModel
from mcp.server import MCPServer
from mcp.server.mcpserver import AcceptedElicitation, Elicit, ElicitationResult, Resolve
from mcp.types import ToolAnnotations

INCIDENTS = {
    "INC-42": {"service": "payments-api", "summary": "checkout payments timed out for 40 min; connection pool exhausted"},
    "INC-57": {"service": "payments-api", "summary": "card payments slow at peak hour; retries piled up"},
    "INC-61": {"service": "auth-service", "summary": "login failures after certificate expired"},
    "INC-70": {"service": "orders-db", "summary": "replica lag showed stale order status"},
}
OWNERS = {"payments-api": "Checkout", "cart-ui": "Checkout", "auth-service": "Identity", "orders-db": "Data Platform"}
MANAGERS = {"Checkout": "Ana Kowalska", "Identity": "Marek Nowak", "Data Platform": "Ola Wiśniewska"}
DEPENDS_ON = {"payments-api": ["auth-service", "orders-db"], "cart-ui": ["payments-api"]}
TICKETS = Path(__file__).with_name("tickets.json")  # ponytail: a JSON file stands in for Jira

mcp = MCPServer("ops")
READ = ToolAnnotations(read_only_hint=True, idempotent_hint=True, open_world_hint=False)


@mcp.tool(annotations=READ)
def get_incident(incident_id: str) -> dict:
    """Look up an incident by ID (e.g. INC-42): affected service and summary."""
    inc = INCIDENTS.get(incident_id.upper())
    return {"incident": incident_id.upper(), **inc} if inc else {"error": f"unknown incident {incident_id}"}


@mcp.tool(annotations=READ)
def service_owner(service: str) -> dict:
    """Which team owns a service, and who manages that team."""
    team = OWNERS.get(service)
    return {"service": service, "team": team, "manager": MANAGERS.get(team)} if team else {"error": f"unknown service {service}"}


@mcp.tool(annotations=READ)
def dependents(service: str) -> dict:
    """Services that break if this service goes down, directly or transitively."""
    out, frontier = [], [service]
    while frontier:
        s = frontier.pop()
        for svc, deps in DEPENDS_ON.items():
            if s in deps and svc not in out:
                out.append(svc); frontier.append(svc)
    return {"service": service, "dependents": out}


class Approval(BaseModel):
    approve: bool


def load_tickets() -> dict:
    return json.loads(TICKETS.read_text()) if TICKETS.exists() else {}


def ask_approval(team: str, title: str, idempotency_key: str):
    """Resolver: runs before create_ticket and asks the human (MCP elicitation) only when a ticket would really be created."""
    if idempotency_key in load_tickets() or team not in MANAGERS:
        return AcceptedElicitation(data=Approval(approve=False))  # nothing to approve: the body returns 'exists' or an error
    return Elicit(f"Create ticket for {team}: '{title}'?", Approval)


@mcp.tool(annotations=ToolAnnotations(read_only_hint=False, destructive_hint=False, idempotent_hint=True, open_world_hint=True))
def create_ticket(team: str, title: str, idempotency_key: str,
                  approval: Annotated[ElicitationResult[Approval], Resolve(ask_approval)]) -> dict:
    """Create a ticket for a team (use the team name from service_owner). Needs human approval.
    Re-sending the same idempotency_key returns the existing ticket instead of creating another."""
    tickets = load_tickets()
    if idempotency_key in tickets:  # a retry or a duplicate call from the model: no second ticket
        return {"status": "exists", **tickets[idempotency_key]}
    if team not in MANAGERS:  # never trust model-supplied arguments
        return {"error": f"unknown team '{team}'", "valid_teams": sorted(MANAGERS)}
    if not isinstance(approval, AcceptedElicitation) or not approval.data.approve:
        return {"status": "declined", "reason": "a human did not approve this action"}
    ticket = {"id": f"OPS-{100 + len(tickets)}", "team": team, "title": title}
    tickets[idempotency_key] = ticket
    TICKETS.write_text(json.dumps(tickets, indent=1))
    return {"status": "created", **ticket}


@mcp.resource("runbook://{service}")
def runbook(service: str) -> str:
    """Runbook text for a service."""
    return {"payments-api": "Raise pool size; add a circuit breaker on retries."}.get(service, "No runbook.")


if __name__ == "__main__":
    mcp.run("stdio")
