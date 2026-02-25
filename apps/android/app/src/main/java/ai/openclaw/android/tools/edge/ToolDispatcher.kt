package ai.openclaw.android.tools.edge

import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Routes LLM function_call outputs to the appropriate [DeviceTool] in the [ToolRegistry].
 *
 * Expected input format (as produced by most LLMs with function calling):
 * ```json
 * { "name": "camera_snap", "arguments": "{\"facing\":\"back\"}" }
 * ```
 * or with arguments already as an object:
 * ```json
 * { "name": "camera_snap", "arguments": {"facing":"back"} }
 * ```
 *
 * @param registry The tool registry to look up tools from
 * @param defaultTimeoutMs Maximum time allowed for any single tool execution (default 30s)
 */
class ToolDispatcher(
  private val registry: ToolRegistry,
  private val defaultTimeoutMs: Long = 30_000L,
) {
  private val json = Json { ignoreUnknownKeys = true }

  /**
   * Dispatch a function call to the appropriate tool.
   *
   * @param functionCall JSON object with "name" and "arguments" fields
   * @return The tool's execution result, or an error ToolResult if dispatch fails
   */
  suspend fun dispatch(functionCall: JsonObject): ToolResult {
    val toolName = functionCall["name"]?.jsonPrimitive?.contentOrNull?.trim()
    if (toolName.isNullOrEmpty()) {
      return ToolResult.error("missing or empty tool name in function_call")
    }

    val tool = registry.findTool(toolName)
      ?: return ToolResult.error("unknown tool: $toolName")

    if (!tool.isAvailable()) {
      return ToolResult.error("tool '$toolName' is not currently available")
    }

    val args = parseArguments(functionCall)
      ?: return ToolResult.error("invalid arguments for tool '$toolName'")

    return try {
      withTimeout(defaultTimeoutMs) {
        tool.execute(args)
      }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
      ToolResult.error("tool '$toolName' timed out after ${defaultTimeoutMs}ms")
    } catch (e: Throwable) {
      ToolResult.error("tool '$toolName' failed: ${e.message ?: e.toString()}")
    }
  }

  /**
   * Parse the "arguments" field from a function_call object.
   * Handles both string-encoded JSON and inline JSON objects.
   */
  private fun parseArguments(functionCall: JsonObject): JsonObject? {
    val raw = functionCall["arguments"] ?: return JsonObject(emptyMap())
    // Already a JsonObject — use directly
    if (raw is JsonObject) return raw
    // String-encoded JSON — parse it
    val str = raw.jsonPrimitive.contentOrNull?.trim() ?: return JsonObject(emptyMap())
    if (str.isEmpty() || str == "{}") return JsonObject(emptyMap())
    return try {
      json.parseToJsonElement(str).jsonObject
    } catch (_: Throwable) {
      null
    }
  }
}
