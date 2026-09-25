package com.example.agent.model

/**
 * Permission classes of a tool.
 *
 *  SAFE       — runs automatically, no approval (current_time, file tools inside the sandbox)
 *  AUTO_SAFE  — read-only network tools that run automatically but are logged more loudly
 *               (web_search, web_fetch). They never mutate anything.
 *  CONFIRM    — destructive or dangerous: requires explicit human approval through the
 *               approval layer (e.g. delete_path outside... actually delete_path stays in
 *               the workspace but is still destructive, terminal-like actions, external
 *               side effects). CONFIRM tools are never advertised to the model until an
 *               approval coordinator is attached that can answer for them.
 */
enum class ToolPermission {
    SAFE,
    AUTO_SAFE,
    CONFIRM
}
