package ai.openclaw.android.tools.edge

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Registry of available [DeviceTool] instances.
 *
 * Maintains a name-keyed map of tools, filters by availability, and generates
 * JSON tool definitions suitable for injection into an LLM system prompt or
 * OpenAI-style function calling `tools` parameter.
 */
class ToolRegistry {
  private val tools = mutableMapOf<String, DeviceTool>()

  /** Register a tool. Replaces any existing tool with the same name. */
  fun register(tool: DeviceTool) {
    tools[tool.name] = tool
  }

  /** Remove a tool by name. */
  fun unregister(name: String) {
    tools.remove(name)
  }

  /** Look up a tool by name (regardless of availability). */
  fun findTool(name: String): DeviceTool? = tools[name]

  /** All registered tools whose [DeviceTool.isAvailable] returns true. */
  fun availableTools(): List<DeviceTool> = tools.values.filter { it.isAvailable() }

  /** All registered tool names. */
  fun registeredNames(): Set<String> = tools.keys.toSet()

  /**
   * Generate an OpenAI-compatible JSON array of tool definitions.
   *
   * Output format per element:
   * ```json
   * {
   *   "type": "function",
   *   "function": {
   *     "name": "camera_snap",
   *     "description": "...",
   *     "parameters": { ... JSON Schema ... }
   *   }
   * }
   * ```
   *
   * Only includes tools where [DeviceTool.isAvailable] is true.
   */
  fun toToolDefinitionsJson(): JsonArray {
    return buildJsonArray {
      for (tool in availableTools()) {
        add(
          buildJsonObject {
            put("type", "function")
            put(
              "function",
              buildJsonObject {
                put("name", tool.name)
                put("description", tool.description)
                put("parameters", tool.parametersSchema)
              },
            )
          },
        )
      }
    }
  }

  /**
   * Generate a plain-text block describing available tools, suitable for
   * embedding directly in a system prompt for models that don't support
   * structured function calling.
   */
  fun toSystemPromptBlock(): String {
    val available = availableTools()
    if (available.isEmpty()) return ""
    return buildString {
      appendLine("You have access to the following device tools.")
      appendLine("To call a tool, output a JSON block: {\"name\":\"<tool>\",\"arguments\":{...}}")
      appendLine()
      for (tool in available) {
        appendLine("### ${tool.name}")
        appendLine(tool.description)
        appendLine("Parameters: ${tool.parametersSchema}")
        appendLine()
      }
    }
  }
}
