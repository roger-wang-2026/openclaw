package ai.openclaw.android.tools.edge

import kotlinx.serialization.json.JsonObject

/**
 * A single message in a conversation with the inference engine.
 */
data class ChatMessage(
  /** Role: "system", "user", "assistant", or "tool" */
  val role: String,
  /** Text content of the message */
  val content: String,
  /** For role="assistant" with a function call: the raw function call JSON */
  val toolCall: JsonObject? = null,
  /** For role="tool": the name of the tool that produced this result */
  val toolName: String? = null,
  /** For role="tool": a tool call ID to correlate with the assistant's request */
  val toolCallId: String? = null,
  /** Optional base64-encoded image to include as multi-modal input */
  val imageBase64: String? = null,
)

/**
 * Result from a single inference call.
 */
data class InferenceResult(
  /** Generated text (may be empty if the model only emitted a tool call) */
  val text: String,
  /** Parsed function_call if the model requested one, e.g. {"name":"camera_snap","arguments":...} */
  val toolCall: JsonObject? = null,
  /** Why the model stopped: "stop", "tool_call", "length", "error" */
  val finishReason: String,
)

/**
 * Abstraction over a local LLM inference engine.
 *
 * Implementations might wrap:
 * - llama.cpp via JNI
 * - MLC-LLM Android SDK
 * - MediaPipe LLM Inference API
 *
 * The interface is deliberately minimal: it takes a message history and returns
 * a single completion. The [EdgeModelChat] layer handles the multi-turn
 * tool-calling loop on top.
 */
interface InferenceEngine {
  /** Whether the engine is loaded and ready to generate. */
  val isReady: Boolean

  /**
   * Run inference on the given message history.
   *
   * @param messages Ordered conversation messages (system → user → assistant → tool → ...)
   * @param toolDefinitions JSON array of available tool definitions (OpenAI format), or null
   * @return The model's response, possibly containing a tool call
   */
  suspend fun complete(
    messages: List<ChatMessage>,
    toolDefinitions: String? = null,
  ): InferenceResult
}

/**
 * Stub inference engine for testing the tool framework without a real LLM.
 *
 * Returns a fixed text response or, if a tool definition is provided,
 * echoes back a function call to the first available tool to verify
 * the dispatch pipeline.
 */
class StubInferenceEngine : InferenceEngine {
  override val isReady: Boolean = true

  var nextResponse: InferenceResult = InferenceResult(
    text = "I'm a stub inference engine. Connect a real LLM to enable intelligent responses.",
    finishReason = "stop",
  )

  override suspend fun complete(
    messages: List<ChatMessage>,
    toolDefinitions: String?,
  ): InferenceResult = nextResponse
}
