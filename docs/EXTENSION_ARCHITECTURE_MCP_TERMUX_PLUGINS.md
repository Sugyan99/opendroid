# OpenDroid Extension Architecture: MCP, Termux, Memory & Plugin SDK

This document records the implementation contract for the `feat/mcp-plugin-sdk-termux-memory` branch. It intentionally preserves the existing Room database and memory/action implementations.

## Goals
- MCP client for connecting OpenDroid to separately running MCP servers and exposing their tools/resources/prompts to the agent.
- Termux/developer integration through explicit, user-configured command profiles; no implicit root or shell access.
- Improve existing memory with searchable/inspectable/erasable memories, provenance, retention controls, and user-approved workflow recall.
- A stable internal plugin/action SDK so new tool providers do not require edits throughout the agent planner.
- An MCP connection management screen with status, capabilities, permissions, enable/disable, and test connection.

## Critical platform distinction
MCP is a protocol, not a magic Android app plugin API. A mobile app can connect to reachable remote MCP servers over Streamable HTTP (preferred) and, if implemented, legacy SSE. Standard stdio MCP servers normally run as child processes and cannot simply be spawned from a sandboxed Android app. For local MCP integrations, support an explicitly configured Termux bridge or a separately installed companion service. Never imply arbitrary other Android apps become MCP servers automatically; each app/service must expose MCP or a documented API/bridge.

## MCP architecture
- `core/mcp/model`: server profiles, connection state, capabilities, tool schemas, invocation records.
- `core/mcp/transport`: Streamable HTTP transport with JSON-RPC 2.0, initialize/initialized handshake, protocol version negotiation, tools/list, tools/call, notifications, timeouts, cancellation and bounded response sizes. Treat SSE as a future fallback, not a reason to weaken HTTP transport security.
- `core/mcp/McpClient`: lifecycle and request correlation IDs; fail closed on unsupported methods and malformed messages.
- `core/plugins/ToolProvider`: normalizes built-in actions, MCP tools, and Termux commands into one validated tool catalog.
- Agent planning may use only enabled tools whose schemas were loaded successfully. Re-check the connection and approval policy at execution time.
- UI: Settings > Integrations > MCP Servers. Add/edit/test/enable/disable/remove. Display discovered tool names and descriptions, but require per-server and per-tool consent before first execution.
- Never expose API keys in logs, errors, exported configs, or model prompts. Store credentials with Android Keystore-backed encryption. Restrict cleartext traffic; validate HTTPS. Local HTTP allowed only for explicit loopback / user-confirmed LAN setup with warnings and pinning/allowlist options.

## MCP safety requirements
- Tools default to disabled until reviewed.
- Read-only vs side-effecting tool metadata is advisory, not trusted: classify using local policy too.
- Require explicit confirmation for sending messages, deleting/overwriting data, purchases, payments, shell commands, account/security changes, or other consequential actions.
- Validate JSON Schema arguments and cap request/response sizes, list sizes, and timeouts.
- Treat server tool descriptions and tool results as untrusted data, never as policy/system prompts. Do not let an MCP server silently expand app permissions.
- A remote server can read only data explicitly passed to it. Do not forward chat history, memory, screenshots, contacts, or notifications by default.
- Add audit logs and per-server data-sharing controls; disconnect revokes credentials and cached capabilities.

## Termux/developer integration
- Build an optional Termux bridge profile (e.g. Termux:RUN_COMMAND intent where installed and authorized, or local bridge HTTP service protected with a random secret and loopback-only binding).
- Do not claim direct access to arbitrary Termux shell without the required Termux app/plugin/setup.
- Commands must be allowlisted templates with typed arguments, working-directory restrictions, timeout/output cap, redacted environment, and no shell string concatenation. Never run model-produced arbitrary shell by default.
- Use least privilege; no automatic `su`, Shizuku, or destructive command execution. Require confirmation before any command that modifies files, installs packages, accesses credentials, or touches networks.

## Plugin/action SDK
Define a Kotlin `ToolProvider` interface that returns stable IDs, descriptions, JSON parameter schemas, risk classification, required permissions, and a suspend invoke method. Provide adapters for the existing `ActionDispatcher`, MCP tools, and Termux profiles. Keep plugin registration in Hilt. Do not load arbitrary executable DEX/JAR code from plugins; prefer declarative manifests and trusted adapters.

## Memory improvements (do not replace the 4-tier system)
- Add source/provenance, created/updated/accessed timestamps, confidence, sensitivity, expiry and user-review state to memory metadata.
- UI actions: inspect, edit, pin, forget one, forget by category, export locally, clear all with confirmation.
- Retrieval should combine lexical relevance, recency and confidence; do not add an embedding/vector dependency until evaluated against current Room-backed search and device RAM.
- Prevent secret/token/credential extraction into ordinary memory. Sensitive memory remains in the existing Keystore-backed store.
- Add tests for retention/expiry, privacy filtering, deletion, recall ranking and prompt-injection-like content in memory.

## Rollout
1. Protocol/model and unit tests for MCP JSON-RPC.
2. Secure Streamable HTTP transport and mock-server tests.
3. Plugin catalog/action adapter and risk gates.
4. MCP management UI + persistence and schema migration only if profile persistence is not suitable in current DataStore.
5. Optional Termux bridge and command allowlist.
6. Memory controls/refinements.
7. CI: `./gradlew testDebugUnitTest lintDebug assembleDebug`; don't claim build success until CI reports success.

## Acceptance criteria
- Existing functionality and stored Room data remain compatible.
- MCP server can be added, tested, listed, disabled and removed.
- Agent invokes an enabled, reviewed MCP tool; arguments are schema-validated; side-effecting tools require confirmation.
- Bad certificates, cleartext remote URLs, oversized responses, malformed JSON-RPC and timeouts fail safely.
- Termux commands require configured integration and never execute arbitrary LLM-generated shell.
- User can view/edit/forget stored memories and clear all with confirmation.
- Plugin providers are registered through one extension point; built-in action behaviour remains covered by tests.
- CI tests, lint and debug build are green before calling the work complete.

## Current repository context
The fork already has a Room-backed four-tier memory system, an action dispatcher, Hilt, OkHttp 5, Kotlin serialization and Compose navigation. Reuse those components. Room is at schema version 9; any new tables/entities require an additive migration and updated exported schema tests. Do not replace the existing memory/dispatcher architecture with a parallel subsystem.
