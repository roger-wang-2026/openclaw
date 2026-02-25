package ai.openclaw.android.tools.edge

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * Result of a device tool execution.
 *
 * @param success Whether the tool executed successfully
 * @param data Structured JSON data returned by the tool
 * @param mediaBase64 Optional base64-encoded binary payload (image, video, etc.)
 * @param mediaType MIME type of the media payload (e.g. "image/jpeg", "video/mp4")
 * @param error Human-readable error message when success is false
 */
data class ToolResult(
  val success: Boolean,
  val data: JsonElement = JsonNull,
  val mediaBase64: String? = null,
  val mediaType: String? = null,
  val error: String? = null,
) {
  companion object {
    fun ok(data: JsonElement, mediaBase64: String? = null, mediaType: String? = null): ToolResult =
      ToolResult(success = true, data = data, mediaBase64 = mediaBase64, mediaType = mediaType)

    fun error(error: String): ToolResult =
      ToolResult(success = false, error = error)
  }
}

/**
 * A device-local tool that can be invoked by an on-device LLM via function calling.
 *
 * Each tool exposes a JSON Schema describing its parameters (OpenAI function-calling format)
 * and an async [execute] method that performs the actual hardware interaction.
 *
 * Implementations wrap existing OpenClaw hardware managers (CameraCaptureManager,
 * LocationCaptureManager, SmsManager, etc.) without modifying them.
 */
interface DeviceTool {
  /** Tool name used in function_call JSON (e.g. "camera_snap", "location_get"). */
  val name: String

  /** Human-readable description shown to the LLM in the system prompt. */
  val description: String

  /** JSON Schema defining accepted parameters (OpenAI function calling format). */
  val parametersSchema: JsonObject

  /** Whether this tool is currently available (permissions granted, hardware present, etc.). */
  fun isAvailable(): Boolean

  /**
   * Execute the tool with the given parameters.
   *
   * @param params Parsed JSON parameters matching [parametersSchema]
   * @return A [ToolResult] containing the output data and optional media
   */
  suspend fun execute(params: JsonObject): ToolResult
}
