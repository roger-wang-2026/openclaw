package ai.openclaw.android.tools.edge

import ai.openclaw.android.node.CameraCaptureManager
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
 * Wraps [CameraCaptureManager.snap] as a [DeviceTool] for on-device LLM function calling.
 *
 * Takes a photo using the device camera and returns a base64-encoded JPEG along with
 * image metadata (width, height, facing direction).
 */
class CameraSnapTool(
  private val camera: CameraCaptureManager,
  private val cameraEnabledFlow: StateFlow<Boolean>,
) : DeviceTool {

  private companion object {
    val JsonParser = Json { ignoreUnknownKeys = true }
  }

  override val name = "camera_snap"

  override val description =
    "Take a photo using the device camera. Returns a JPEG image. " +
    "Use facing='front' for selfie camera or facing='back' for the rear camera."

  override val parametersSchema: JsonObject = buildJsonObject {
    put("type", "object")
    put(
      "properties",
      buildJsonObject {
        put(
          "facing",
          buildJsonObject {
            put("type", "string")
            put("description", "Camera direction: 'front' (selfie) or 'back' (rear). Default: back")
          },
        )
        put(
          "maxWidth",
          buildJsonObject {
            put("type", "integer")
            put("description", "Maximum image width in pixels. Omit for full resolution.")
          },
        )
        put(
          "quality",
          buildJsonObject {
            put("type", "integer")
            put("description", "JPEG quality 1-100. Default: 85")
          },
        )
      },
    )
  }

  override fun isAvailable(): Boolean = cameraEnabledFlow.value

  override suspend fun execute(params: JsonObject): ToolResult {
    val facing = params["facing"]?.jsonPrimitive?.contentOrNull?.trim() ?: "back"
    val maxWidth = params["maxWidth"]?.jsonPrimitive?.intOrNull
    val quality = params["quality"]?.jsonPrimitive?.intOrNull

    val paramsJson = buildJsonObject {
      put("facing", facing)
      put("format", "jpg")
      maxWidth?.let { put("maxWidth", it) }
      quality?.let { put("quality", it) }
    }.toString()

    return try {
      val result = camera.snap(paramsJson)
      // CameraCaptureManager.Payload contains payloadJson with format, base64, width, height
      val payloadObj = JsonParser
        .parseToJsonElement(result.payloadJson).jsonObject

      val base64 = payloadObj["base64"]?.jsonPrimitive?.contentOrNull
      val width = payloadObj["width"]?.jsonPrimitive?.intOrNull ?: 0
      val height = payloadObj["height"]?.jsonPrimitive?.intOrNull ?: 0

      ToolResult.ok(
        data = buildJsonObject {
          put("facing", facing)
          put("width", width)
          put("height", height)
          put("format", "jpeg")
        },
        mediaBase64 = base64,
        mediaType = "image/jpeg",
      )
    } catch (e: Throwable) {
      ToolResult.error("camera_snap failed: ${e.message ?: e.toString()}")
    }
  }
}
