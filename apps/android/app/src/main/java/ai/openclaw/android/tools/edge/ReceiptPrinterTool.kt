package ai.openclaw.android.tools.edge

import ai.openclaw.android.tools.commercial.ReceiptPrinterManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Wraps [ReceiptPrinterManager] as a [DeviceTool] for on-device LLM function calling.
 *
 * Supports printing text receipts and base64-encoded images.
 */
class ReceiptPrinterTool(
  private val printer: ReceiptPrinterManager,
) : DeviceTool {

  override val name = "receipt_print"

  override val description =
    "Print a receipt on the built-in thermal printer. " +
    "Can print plain text or a base64-encoded image. " +
    "Provide either 'text' or 'image_base64', not both."

  override val parametersSchema: JsonObject = buildJsonObject {
    put("type", "object")
    put(
      "properties",
      buildJsonObject {
        put(
          "text",
          buildJsonObject {
            put("type", "string")
            put("description", "Plain text content to print on the receipt")
          },
        )
        put(
          "image_base64",
          buildJsonObject {
            put("type", "string")
            put("description", "Base64-encoded image (PNG/JPEG) to print")
          },
        )
      },
    )
  }

  override fun isAvailable(): Boolean = printer.isAvailable()

  override suspend fun execute(params: JsonObject): ToolResult {
    val text = params["text"]?.jsonPrimitive?.contentOrNull
    val imageBase64 = params["image_base64"]?.jsonPrimitive?.contentOrNull

    if (text.isNullOrEmpty() && imageBase64.isNullOrEmpty()) {
      return ToolResult.error("receipt_print: provide either 'text' or 'image_base64'")
    }

    return try {
      val result = if (!text.isNullOrEmpty()) {
        printer.printText(text)
      } else {
        printer.printImage(imageBase64!!)
      }

      if (result.ok) {
        ToolResult.ok(
          data = buildJsonObject {
            put("ok", true)
            put("mode", if (!text.isNullOrEmpty()) "text" else "image")
          },
        )
      } else {
        ToolResult.error("receipt_print failed: ${result.error ?: "unknown error"}")
      }
    } catch (e: Throwable) {
      ToolResult.error("receipt_print failed: ${e.message ?: e.toString()}")
    }
  }
}
