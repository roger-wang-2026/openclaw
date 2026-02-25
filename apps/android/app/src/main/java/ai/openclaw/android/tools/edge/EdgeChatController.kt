package ai.openclaw.android.tools.edge

import ai.openclaw.android.chat.ChatMessage
import ai.openclaw.android.chat.ChatMessageContent
import ai.openclaw.android.chat.ChatPendingToolCall
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Local-inference chat controller that mirrors the public API surface of
 * [ai.openclaw.android.chat.ChatController] but uses [EdgeModelChat] for
 * on-device inference instead of the remote Gateway.
 *
 * Important differences from the remote controller:
 * - No Gateway / WebSocket dependency
 * - Health is always "ok" (the model is local)
 * - Streaming text is published after inference completes (not token-by-token,
 *   unless the engine supports streaming callbacks in the future)
 * - Tool calls are displayed as [ChatPendingToolCall] during execution
 */
class EdgeChatController(
  private val scope: CoroutineScope,
  private var edgeChat: EdgeModelChat,
) {
  private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
  val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

  private val _errorText = MutableStateFlow<String?>(null)
  val errorText: StateFlow<String?> = _errorText.asStateFlow()

  private val _streamingAssistantText = MutableStateFlow<String?>(null)
  val streamingAssistantText: StateFlow<String?> = _streamingAssistantText.asStateFlow()

  private val _pendingToolCalls = MutableStateFlow<List<ChatPendingToolCall>>(emptyList())
  val pendingToolCalls: StateFlow<List<ChatPendingToolCall>> = _pendingToolCalls.asStateFlow()

  private val _isProcessing = MutableStateFlow(false)
  val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

  private var activeJob: Job? = null

  /**
   * Replace the underlying [EdgeModelChat] (e.g. when the inference engine
   * is swapped after loading a real model). Clears message history.
   */
  fun updateEdgeChat(newChat: EdgeModelChat) {
    abort()
    edgeChat = newChat
    _messages.value = emptyList()
    _errorText.value = null
    _streamingAssistantText.value = null
  }

  /**
   * Send a user message to the local LLM.
   *
   * Mirrors [ai.openclaw.android.chat.ChatController.sendMessage]:
   * appends a user message optimistically, then runs inference with tool
   * calling and appends the assistant response when done.
   */
  fun sendMessage(message: String) {
    val trimmed = message.trim()
    if (trimmed.isEmpty()) return
    if (_isProcessing.value) {
      _errorText.value = "Already processing a message"
      return
    }

    // Optimistic user message
    val userMsg = ChatMessage(
      id = UUID.randomUUID().toString(),
      role = "user",
      content = listOf(ChatMessageContent(type = "text", text = trimmed)),
      timestampMs = System.currentTimeMillis(),
    )
    _messages.value = _messages.value + userMsg

    _errorText.value = null
    _streamingAssistantText.value = null
    _isProcessing.value = true

    activeJob = scope.launch {
      try {
        val response = edgeChat.chat(trimmed)

        // Build tool-call records as content items for the assistant message
        val contentItems = buildList {
          // Show tool usage summary if any
          for (record in response.toolCallHistory) {
            add(
              ChatMessageContent(
                type = "text",
                text = "🔧 ${record.toolName}(${record.arguments})",
              ),
            )
            // If the tool returned media, include it
            if (record.result.mediaBase64 != null) {
              add(
                ChatMessageContent(
                  type = "image",
                  mimeType = record.result.mediaType ?: "application/octet-stream",
                  base64 = record.result.mediaBase64,
                ),
              )
            }
          }
          // Final text response
          add(ChatMessageContent(type = "text", text = response.text))
        }

        val assistantMsg = ChatMessage(
          id = UUID.randomUUID().toString(),
          role = "assistant",
          content = contentItems,
          timestampMs = System.currentTimeMillis(),
        )
        _messages.value = _messages.value + assistantMsg
        _streamingAssistantText.value = null
      } catch (e: Throwable) {
        _errorText.value = e.message ?: "Edge inference failed"
      } finally {
        _isProcessing.value = false
        _pendingToolCalls.value = emptyList()
      }
    }
  }

  /** Abort the current inference (best-effort). */
  fun abort() {
    activeJob?.cancel()
    activeJob = null
    _isProcessing.value = false
    _streamingAssistantText.value = null
    _pendingToolCalls.value = emptyList()
  }

  /** Clear all message history. */
  fun clearHistory() {
    edgeChat.clearHistory()
    _messages.value = emptyList()
    _errorText.value = null
    _streamingAssistantText.value = null
  }
}
