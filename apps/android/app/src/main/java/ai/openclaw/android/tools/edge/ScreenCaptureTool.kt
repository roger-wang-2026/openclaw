package ai.openclaw.android.tools.edge

import ai.openclaw.android.node.ScreenRecordManager
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Wraps [ScreenRecordManager] as a [DeviceTool] for on-device LLM function calling.
 *
 * Records the device screen for a specified duration and returns the result as
 * a base64-encoded video with metadata.
 */
class ScreenCaptureTool(
  private val screenRecorder: ScreenRecordManager,
  private val screenRecordActiveFlow: StateFlow<Boolean>,
) : DeviceTool {

  private companion object {
    val JsonParser = Json { ignoreUnknownKeys = true }
  }

  override val name = "screen_record"

  override val description =
    "Record the device screen for a given duration and return the video. " +
    "Use this to capture what is currently displayed on the screen."

  override val parametersSchema: JsonObject = buildJsonObject {
    put("type", "object")
    put(
      "properties",
      buildJsonObject {
        put(
          "durationMs",
          buildJsonObject {
            put("type", "integer")
            put("description", "Recording duration in milliseconds. Default: 5000, max: 30000")
          },
        )
        put(
          "fps",
          buildJsonObject {
            put("type", "integer")
            put("description", "Frames per second. Default: 10")
          },
        )
      },
    )
  }

  override fun isAvailable(): Boolean = !screenRecordActiveFlow.value

  override suspend fun execute(params: JsonObject): ToolResult {
    val durationMs = (params["durationMs"]?.jsonPrimitive?.intOrNull ?: 5000)
      .coerceIn(1000, 30_000)
    val fps = (params["fps"]?.jsonPrimitive?.intOrNull ?: 10)
      .coerceIn(1, 30)

    val paramsJson = buildJsonObject {
      put("durationMs", durationMs)
      put("fps", fps)
      put("format", "mp4")
    }.toString()

    return try {
      val result = screenRecorder.record(paramsJson)
      val payloadObj = JsonParser
        .parseToJsonElement(result.payloadJson).jsonObject

      val base64 = payloadObj["base64"]?.jsonPrimitive?.contentOrNull
      val actualDuration = payloadObj["durationMs"]?.jsonPrimitive?.intOrNull

      ToolResult.ok(
        data = buildJsonObject {
          put("format", "mp4")
          put("durationMs", actualDuration ?: durationMs)
          put("fps", fps)
        },
        mediaBase64 = base64,
        mediaType = "video/mp4",
      )
    } catch (e: Throwable) {
      ToolResult.error("screen_record failed: ${e.message ?: e.toString()}")
    }
  }
}
