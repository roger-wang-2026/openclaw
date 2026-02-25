package ai.openclaw.android.tools.edge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Response from a complete chat session, including any tool calls that occurred.
 */
data class ChatResponse(
  /** Final text response from the model */
  val text: String,
  /** Whether any tools were called during this chat turn */
  val usedTools: Boolean,
  /** History of tool calls made during this turn (for display purposes) */
  val toolCallHistory: List<ToolCallRecord> = emptyList(),
)

/**
 * Record of a single tool call that occurred during a chat turn.
 */
data class ToolCallRecord(
  val toolName: String,
  val arguments: String,
  val result: ToolResult,
)

/**
 * Manages a multi-turn conversation with an on-device LLM, handling the
 * function-calling loop automatically.
 *
 * Flow:
 * 1. User sends a message
 * 2. Build system prompt (inject available tool definitions)
 * 3. Call inference engine
 * 4. If the model emits a function_call → dispatch through ToolDispatcher → inject result → re-infer
 * 5. Repeat step 4 up to [maxToolRounds] times
 * 6. Return the final text response
 *
 * @param engine The local inference engine (llama.cpp, MLC-LLM, etc.)
 * @param registry Tool registry providing available tool definitions
 * @param dispatcher Tool dispatcher for executing function calls
 * @param maxToolRounds Maximum number of tool-calling rounds per user message (default 5)
 */
class EdgeModelChat(
  private val engine: InferenceEngine,
  private val registry: ToolRegistry,
  private val dispatcher: ToolDispatcher,
  private val maxToolRounds: Int = 5,
) {
  private val json = Json { ignoreUnknownKeys = true }
  private val messageHistory = mutableListOf<ChatMessage>()

  /** Clear conversation history. */
  fun clearHistory() {
    messageHistory.clear()
  }

  /** Current message count in history. */
  val historySize: Int get() = messageHistory.size

  /**
   * Process a user message, run inference, and handle any tool calls.
   *
   * @param userMessage The user's input text
   * @return A [ChatResponse] with the final model output and tool usage info
   */
  suspend fun chat(userMessage: String): ChatResponse {
    // Add user message to history
    messageHistory.add(ChatMessage(role = "user", content = userMessage))

    // Build the full message list with system prompt
    val systemPrompt = buildSystemPrompt()
    val toolDefsJson = registry.toToolDefinitionsJson().toString()
      .takeIf { it != "[]" }

    val toolCallHistory = mutableListOf<ToolCallRecord>()
    var rounds = 0

    while (rounds <= maxToolRounds) {
      val messages = buildMessages(systemPrompt)
      val result = engine.complete(messages, toolDefsJson)

      if (result.toolCall != null && rounds < maxToolRounds) {
        // Model wants to call a tool
        val toolCallId = UUID.randomUUID().toString().take(8)
        val toolName = result.toolCall["name"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        val argsStr = result.toolCall["arguments"]?.toString() ?: "{}"

        // Record the assistant's tool call in history
        messageHistory.add(
          ChatMessage(
            role = "assistant",
            content = result.text,
            toolCall = result.toolCall,
          ),
        )

        // Dispatch to actual tool
        val toolResult = dispatcher.dispatch(result.toolCall)
        toolCallHistory.add(ToolCallRecord(toolName, argsStr, toolResult))

        // Build the tool result message
        val toolResultContent = buildToolResultContent(toolResult)
        messageHistory.add(
          ChatMessage(
            role = "tool",
            content = toolResultContent,
            toolName = toolName,
            toolCallId = toolCallId,
            imageBase64 = toolResult.mediaBase64,
          ),
        )

        rounds++
      } else {
        // Model produced a final text response (or max rounds reached)
        val finalText = result.text.ifBlank {
          if (toolCallHistory.isNotEmpty()) {
            "Done. Used ${toolCallHistory.size} tool(s)."
          } else {
            "(no response)"
          }
        }
        messageHistory.add(ChatMessage(role = "assistant", content = finalText))
        return ChatResponse(
          text = finalText,
          usedTools = toolCallHistory.isNotEmpty(),
          toolCallHistory = toolCallHistory,
        )
      }
    }

    // Should not reach here, but safety fallback
    val fallback = "Reached maximum tool call rounds ($maxToolRounds)."
    messageHistory.add(ChatMessage(role = "assistant", content = fallback))
    return ChatResponse(
      text = fallback,
      usedTools = toolCallHistory.isNotEmpty(),
      toolCallHistory = toolCallHistory,
    )
  }

  private fun buildSystemPrompt(): String {
    val toolBlock = registry.toSystemPromptBlock()
    return buildString {
      appendLine("You are an AI assistant running locally on an Android device.")
      appendLine("You can interact with the device hardware through tools.")
      appendLine()
      if (toolBlock.isNotEmpty()) {
        appendLine(toolBlock)
      }
      appendLine("Rules:")
      appendLine("- For sensitive operations (sending SMS, etc.), confirm with the user first.")
      appendLine("- Describe media content (photos, videos) after receiving tool results.")
      appendLine("- Be concise and helpful.")
    }
  }

  private fun buildMessages(systemPrompt: String): List<ChatMessage> {
    return listOf(ChatMessage(role = "system", content = systemPrompt)) + messageHistory
  }

  private fun buildToolResultContent(result: ToolResult): String {
    return buildJsonObject {
      put("success", result.success)
      if (result.success) {
        put("data", result.data)
        if (result.mediaBase64 != null) {
          put("hasMedia", true)
          put("mediaType", result.mediaType ?: "application/octet-stream")
        }
      } else {
        put("error", result.error ?: "unknown error")
      }
    }.toString()
  }
}
