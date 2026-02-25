package ai.openclaw.android.tools.edge

import ai.openclaw.android.tools.commercial.BarcodeScannerManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * Wraps [BarcodeScannerManager] as a [DeviceTool] for on-device LLM function calling.
 *
 * Triggers the barcode/QR scanner and returns the decoded content.
 */
class BarcodeScannerTool(
  private val scanner: BarcodeScannerManager,
) : DeviceTool {

  override val name = "barcode_scan"

  override val description =
    "Scan a barcode or QR code using the device scanner. " +
    "Returns the barcode content and format (e.g. QR_CODE, EAN_13, CODE_128)."

  override val parametersSchema: JsonObject = buildJsonObject {
    put("type", "object")
    put(
      "properties",
      buildJsonObject {
        put(
          "timeout_ms",
          buildJsonObject {
            put("type", "integer")
            put("description", "Maximum wait time in milliseconds (default 10000)")
          },
        )
      },
    )
  }

  override fun isAvailable(): Boolean = scanner.isAvailable()

  override suspend fun execute(params: JsonObject): ToolResult {
    val timeoutMs = params["timeout_ms"]?.jsonPrimitive?.long ?: 10_000L

    return try {
      val result = scanner.scan(timeoutMs)
      if (result.ok && result.content != null) {
        ToolResult.ok(
          data = buildJsonObject {
            put("content", result.content)
            put("format", result.format ?: "UNKNOWN")
          },
        )
      } else {
        ToolResult.error("barcode_scan failed: ${result.error ?: "no barcode detected"}")
      }
    } catch (e: Throwable) {
      ToolResult.error("barcode_scan failed: ${e.message ?: e.toString()}")
    }
  }
}
