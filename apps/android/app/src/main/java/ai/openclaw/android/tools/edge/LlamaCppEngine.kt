package ai.openclaw.android.tools.edge

import android.content.ContentResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.nehuatl.llamacpp.LlamaHelper
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Real [InferenceEngine] implementation backed by llama.cpp via the
 * `kotlinllamacpp` library (`org.nehuatl.llamacpp`).
 *
 * Lifecycle:
 * 1. Construct with a [ContentResolver]
 * 2. Call [loadModel] with the path to a .gguf file
 * 3. Use [complete] for inference (called by [EdgeModelChat])
 * 4. Call [unload] when done
 *
 * The engine formats messages as ChatML and parses `<tool_call>` blocks
 * from the generated text to detect function calling.
 */
class LlamaCppEngine(
  contentResolver: ContentResolver,
) : InferenceEngine {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val json = Json { ignoreUnknownKeys = true }

  private val llmFlow = MutableSharedFlow<LlamaHelper.LLMEvent>(
    replay = 0,
    extraBufferCapacity = 256,
  )

  private val llamaHelper = LlamaHelper(
    contentResolver = contentResolver,
    scope = scope,
    sharedFlow = llmFlow,
  )

  private var _isReady = false
  override val isReady: Boolean get() = _isReady

  /**
   * Load a GGUF model file from the given [path].
   *
   * @param path Absolute path to the .gguf file on device storage
   * @param contextLength Max context window (tokens). Defaults to 2048.
   */
  fun loadModel(path: String, contextLength: Int = 2048) {
    llamaHelper.load(path, contextLength) {
      _isReady = true
    }
  }

  /**
   * Release the loaded model and free resources.
   */
  fun unload() {
    _isReady = false
    llamaHelper.abort()
    llamaHelper.release()
  }

  override suspend fun complete(
    messages: List<ChatMessage>,
    toolDefinitions: String?,
  ): InferenceResult {
    if (!_isReady) {
      return InferenceResult(
        text = "Model not loaded. Call loadModel() first.",
        finishReason = "error",
      )
    }

    val prompt = buildChatMLPrompt(messages, toolDefinitions)
    return runInference(prompt)
  }

  /**
   * Run prediction and collect tokens into a single result.
   */
  private suspend fun runInference(prompt: String): InferenceResult =
    suspendCancellableCoroutine { cont ->
      val sb = StringBuilder()
      var resumed = false

      val job = scope.launch {
        llamaHelper.predict(prompt)
        llmFlow.collect { event ->
          if (resumed) return@collect
          when (event) {
            is LlamaHelper.LLMEvent.Ongoing -> {
              sb.append(event.word)
            }
            is LlamaHelper.LLMEvent.Done -> {
              resumed = true
              val generated = sb.toString().trim()
              cont.resume(parseGeneratedText(generated))
            }
            is LlamaHelper.LLMEvent.Error -> {
              resumed = true
              cont.resumeWithException(
                RuntimeException("Inference error: ${event.message}"),
              )
            }
            else -> { /* Started / Loaded — ignore */ }
          }
        }
      }

      cont.invokeOnCancellation {
        job.cancel()
        llamaHelper.stopPrediction()
      }
    }

  // ── Prompt formatting ─────────────────────────────────────────────

  /**
   * Build a ChatML-formatted prompt from the message history.
   *
   * Format:
   * ```
   * <|im_start|>system
   * You are a helpful assistant with access to tools...
   * <|im_end|>
   * <|im_start|>user
   * ...
   * <|im_end|>
   * <|im_start|>assistant
   * ```
   */
  private fun buildChatMLPrompt(
    messages: List<ChatMessage>,
    toolDefinitions: String?,
  ): String = buildString {
    for (msg in messages) {
      append("<|im_start|>")
      append(msg.role)
      append("\n")

      // For system messages with tools, inject tool definitions
      if (msg.role == "system" && !toolDefinitions.isNullOrBlank()) {
        append(msg.content)
        append("\n\nYou have access to the following tools:\n")
        append(toolDefinitions)
        append("\n\nTo use a tool, output a JSON block wrapped in <tool_call> tags:\n")
        append("<tool_call>\n")
        append("""{"name": "tool_name", "arguments": {"arg": "value"}}""")
        append("\n</tool_call>")
      } else if (msg.role == "tool") {
        // Tool results: format as [tool_name] result
        append("[${msg.toolName ?: "tool"}] ")
        append(msg.content)
      } else {
        append(msg.content)
      }

      append("\n<|im_end|>\n")
    }

    // Prompt the assistant to respond
    append("<|im_start|>assistant\n")
  }

  // ── Response parsing ─────────────────────────────────────────────

  /**
   * Parse generated text for tool calls.
   *
   * If the text contains `<tool_call>...</tool_call>`, extract the JSON
   * and return it as a function call. Otherwise return as plain text.
   */
  private fun parseGeneratedText(text: String): InferenceResult {
    val toolCallMatch = TOOL_CALL_REGEX.find(text)
    if (toolCallMatch != null) {
      val jsonStr = toolCallMatch.groupValues[1].trim()
      return try {
        val parsed = json.parseToJsonElement(jsonStr)
        if (parsed is JsonObject) {
          InferenceResult(
            text = text.replace(toolCallMatch.value, "").trim(),
            toolCall = parsed,
            finishReason = "tool_call",
          )
        } else {
          InferenceResult(text = text, finishReason = "stop")
        }
      } catch (_: Throwable) {
        // Malformed JSON in tool_call block — treat as plain text
        InferenceResult(text = text, finishReason = "stop")
      }
    }

    // Also handle OpenAI-style function_call JSON (some models output this)
    val funcCallMatch = FUNC_CALL_REGEX.find(text)
    if (funcCallMatch != null) {
      return try {
        val parsed = json.parseToJsonElement(funcCallMatch.value)
        if (parsed is JsonObject &&
          parsed.containsKey("name") &&
          parsed.containsKey("arguments")
        ) {
          InferenceResult(
            text = text.replace(funcCallMatch.value, "").trim(),
            toolCall = parsed,
            finishReason = "tool_call",
          )
        } else {
          InferenceResult(text = text, finishReason = "stop")
        }
      } catch (_: Throwable) {
        InferenceResult(text = text, finishReason = "stop")
      }
    }

    return InferenceResult(text = text, finishReason = "stop")
  }

  companion object {
    /** Matches `<tool_call>{ ... }</tool_call>` blocks */
    private val TOOL_CALL_REGEX =
      Regex("""<tool_call>\s*(\{[\s\S]*?\})\s*</tool_call>""")

    /** Matches standalone `{"name":"...","arguments":{...}}` JSON */
    private val FUNC_CALL_REGEX =
      Regex("""\{"name"\s*:\s*"[^"]+"\s*,\s*"arguments"\s*:\s*\{[^}]*\}\s*\}""")
  }
}
