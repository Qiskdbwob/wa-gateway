package com.example.agent.mcp

import com.example.agent.model.Tool
import com.example.agent.model.ToolPermission
import com.example.agent.model.ToolResult
import com.example.agent.storage.dao.McpServerDao
import com.example.agent.storage.entity.McpServerEntity
import com.example.agent.tool.ToolRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Lifecycle for MCP servers: store them, discover their tools, and keep the tool registry in sync.
 *
 * The value of MCP here is leverage — one entry in Settings can add a dozen tools the app never
 * had to write. The registry is therefore refreshed (not rebuilt) whenever servers change: tools
 * from a removed or disabled server disappear immediately instead of lingering as ghost tools the
 * model could still call.
 */
class McpManager(
    private val dao: McpServerDao,
    private val registry: () -> ToolRegistry,
    /** `SecretCipher.decrypt` — headers may carry bearer tokens, so they are stored encrypted. */
    private val decrypt: (String) -> String,
    private val encrypt: (String) -> String,
    private val clientFactory: (String, Map<String, String>) -> McpClient = { url, headers ->
        McpClient(url, headers)
    },
    private val log: (String, String) -> Unit = { _, _ -> }
) {

    private val _servers = MutableStateFlow<List<McpServerEntity>>(emptyList())
    val servers: StateFlow<List<McpServerEntity>> = _servers.asStateFlow()

    /** serverId → human readable state ("3 tool terdaftar", "error: ..."). */
    private val _statuses = MutableStateFlow<Map<String, String>>(emptyMap())
    val statuses: StateFlow<Map<String, String>> = _statuses.asStateFlow()

    private val clients = ConcurrentHashMap<String, McpClient>()

    /** local tool name → serverId, so a refresh can remove exactly what it previously added. */
    private val registered = ConcurrentHashMap<String, String>()

    suspend fun load() {
        _servers.value = dao.getAll()
    }

    /** Validates and stores a server, then discovers its tools right away. */
    suspend fun add(name: String, url: String, headers: String): Result<McpServerEntity> {
        val cleanName = name.trim()
        val cleanUrl = url.trim()
        if (cleanName.isEmpty()) return Result.failure(IllegalArgumentException("Nama server wajib diisi."))
        if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            return Result.failure(IllegalArgumentException("URL harus dimulai dengan http:// atau https://"))
        }
        if (dao.findByName(cleanName) != null) {
            return Result.failure(IllegalArgumentException("Server bernama '$cleanName' sudah ada."))
        }

        val entity = McpServerEntity(
            id = UUID.randomUUID().toString(),
            name = cleanName,
            url = cleanUrl,
            headers = encrypt(headers.trim())
        )
        dao.upsert(entity)
        load()
        refresh()
        return Result.success(entity)
    }

    suspend fun remove(id: String) {
        val server = dao.findById(id)
        if (server != null) {
            // Fail loudly in the log rather than silently leaving remote tools behind.
            registered.filterValues { it == id }.keys.forEach { localName ->
                runCatching { registry().unregister(localName) }
                registered.remove(localName)
            }
            clients.remove(id)
        }
        dao.deleteById(id)
        _statuses.value = _statuses.value - id
        load()
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        dao.setEnabled(id, enabled, System.currentTimeMillis())
        load()
        refresh()
    }

    /** Re-discovers tools for every enabled server; disabled/removed ones lose their tools. */
    suspend fun refresh() {
        val servers = dao.getAll()
        _servers.value = servers

        val enabled = servers.filter { it.enabled }
        val enabledIds = enabled.map { it.id }.toSet()

        registered.filterValues { it !in enabledIds }.keys.toList().forEach { localName ->
            runCatching { registry().unregister(localName) }
            registered.remove(localName)
        }

        val statuses = mutableMapOf<String, String>()
        for (server in servers) {
            if (!server.enabled) {
                statuses[server.id] = "nonaktif"
                continue
            }
            try {
                val headers = if (server.headers.isBlank()) {
                    emptyMap()
                } else {
                    McpProtocol.parseHeaders(decrypt(server.headers))
                }
                val client = clients.getOrPut(server.id) { clientFactory(server.url, headers) }

                val serverInfo = client.initialize().getOrElse { error ->
                    statuses[server.id] = "error: ${error.message}"
                    log("MCP_ERROR", "${server.name}: ${error.message}")
                    null
                } ?: continue

                val tools = client.listTools().getOrElse { error ->
                    statuses[server.id] = "error: ${error.message}"
                    log("MCP_ERROR", "${server.name}: ${error.message}")
                    emptyList()
                }

                var count = 0
                for (descriptor in tools) {
                    val localName = McpProtocol.toolName(server.name, descriptor.name)
                    registry().register(
                        McpTool(
                            serverName = server.name,
                            descriptor = descriptor,
                            localName = localName,
                            call = { arguments -> client.callTool(descriptor.name, arguments) }
                        )
                    )
                    registered[localName] = server.id
                    count++
                }
                statuses[server.id] = "$count tool terdaftar"
                log("MCP", "${server.name} ($serverInfo): $count tool")
            } catch (e: Exception) {
                statuses[server.id] = "error: ${e.message}"
                log("MCP_ERROR", "${server.name}: ${e.message}")
            }
        }
        _statuses.value = statuses
    }

    /** Local names currently contributed by MCP servers. */
    fun toolNames(): Set<String> = registered.keys.toSet()
}

/**
 * One remote MCP tool, presented to the model like any other tool.
 *
 * `AUTO_SAFE` — adding the server in Settings is the user's consent for its tools, and a read-only
 * local gate cannot know what a remote tool does. The description is prefixed with the server name
 * so a model reading the tool list can tell where a call is going.
 */
class McpTool(
    private val serverName: String,
    private val descriptor: McpToolDescriptor,
    private val localName: String,
    private val call: suspend (String) -> Result<String>
) : Tool {

    override val id: String = "mcp.${serverName.lowercase().replace(' ', '-')}.${descriptor.name}"
    override val name: String = localName
    override val description: String =
        "[MCP $serverName] ${descriptor.description.ifBlank { "Tool dari server MCP $serverName." }}"
    override val inputSchema: String =
        descriptor.inputSchema.ifBlank { """{"type":"object","properties":{},"required":[]}""" }
    override val permission: ToolPermission = ToolPermission.AUTO_SAFE

    override suspend fun execute(input: String): ToolResult {
        val result = call(input)
        return result.fold(
            onSuccess = { output -> ToolResult(success = true, output = output) },
            onFailure = { error ->
                ToolResult(
                    success = false,
                    output = "",
                    error = "MCP $serverName/${descriptor.name} gagal: ${error.message}"
                )
            }
        )
    }
}
