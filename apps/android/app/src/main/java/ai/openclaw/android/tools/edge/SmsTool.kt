package ai.openclaw.android.tools.edge

import ai.openclaw.android.node.SmsManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Wraps [SmsManager.send] as a [DeviceTool] for on-device LLM function calling.
 *
 * Sends an SMS text message to a given phone number. Requires SEND_SMS permission
 * and telephony hardware to be available.
 */
class SmsTool(
  private val sms: SmsManager,
) : DeviceTool {

  override val name = "sms_send"

  override val description =
    "Send an SMS text message to a phone number. " +
    "IMPORTANT: Always confirm with the user before sending. " +
    "Requires SMS permission and telephony capability."

  override val parametersSchema: JsonObject = buildJsonObject {
    put("type", "object")
    put(
      "properties",
      buildJsonObject {
        put(
          "to",
          buildJsonObject {
            put("type", "string")
            put("description", "Recipient phone number (e.g. '+8613800138000')")
          },
        )
        put(
          "message",
          buildJsonObject {
            put("type", "string")
            put("description", "Text message content to send")
          },
        )
      },
    )
    put(
      "required",
      kotlinx.serialization.json.buildJsonArray {
        add(kotlinx.serialization.json.JsonPrimitive("to"))
        add(kotlinx.serialization.json.JsonPrimitive("message"))
      },
    )
  }

  override fun isAvailable(): Boolean = sms.canSendSms()

  override suspend fun execute(params: JsonObject): ToolResult {
    val to = params["to"]?.jsonPrimitive?.contentOrNull?.trim()
    val message = params["message"]?.jsonPrimitive?.contentOrNull

    if (to.isNullOrEmpty()) {
      return ToolResult.error("sms_send: 'to' phone number is required")
    }
    if (message.isNullOrEmpty()) {
      return ToolResult.error("sms_send: 'message' text is required")
    }

    val paramsJson = buildJsonObject {
      put("to", to)
      put("message", message)
    }.toString()

    return try {
      val result = sms.send(paramsJson)
      if (result.ok) {
        ToolResult.ok(
          data = buildJsonObject {
            put("ok", true)
            put("to", to)
          },
        )
      } else {
        ToolResult.error("sms_send failed: ${result.error ?: "unknown error"}")
      }
    } catch (e: Throwable) {
      ToolResult.error("sms_send failed: ${e.message ?: e.toString()}")
    }
  }
}
