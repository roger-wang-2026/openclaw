package ai.openclaw.android.tools.edge

import ai.openclaw.android.tools.commercial.NfcCardReaderManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * Wraps [NfcCardReaderManager] as a [DeviceTool] for on-device LLM function calling.
 *
 * Reads NFC/IC card UID and basic information.
 */
class NfcCardReaderTool(
  private val reader: NfcCardReaderManager,
) : DeviceTool {

  override val name = "nfc_read_card"

  override val description =
    "Read an NFC or IC card. Wait for the user to tap a card, " +
    "then return the card UID, type, and any readable data."

  override val parametersSchema: JsonObject = buildJsonObject {
    put("type", "object")
    put(
      "properties",
      buildJsonObject {
        put(
          "timeout_ms",
          buildJsonObject {
            put("type", "integer")
            put("description", "Maximum wait time in milliseconds (default 15000)")
          },
        )
      },
    )
  }

  override fun isAvailable(): Boolean = reader.isAvailable()

  override suspend fun execute(params: JsonObject): ToolResult {
    val timeoutMs = params["timeout_ms"]?.jsonPrimitive?.long ?: 15_000L

    return try {
      val result = reader.readCard(timeoutMs)
      if (result.ok && result.uid != null) {
        ToolResult.ok(
          data = buildJsonObject {
            put("uid", result.uid)
            put("card_type", result.cardType ?: "UNKNOWN")
            if (result.data != null) {
              put("data", result.data)
            }
          },
        )
      } else {
        ToolResult.error("nfc_read_card failed: ${result.error ?: "no card detected"}")
      }
    } catch (e: Throwable) {
      ToolResult.error("nfc_read_card failed: ${e.message ?: e.toString()}")
    }
  }
}
