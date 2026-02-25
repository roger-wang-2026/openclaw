package ai.openclaw.android.tools.edge

import ai.openclaw.android.LocationMode
import ai.openclaw.android.node.LocationCaptureManager
import android.location.LocationManager
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Wraps [LocationCaptureManager.getLocation] as a [DeviceTool] for on-device LLM function calling.
 *
 * Returns the device's current GPS/network location including latitude, longitude,
 * accuracy, altitude, speed, and heading when available.
 */
class LocationTool(
  private val location: LocationCaptureManager,
  private val locationModeFlow: StateFlow<LocationMode>,
  private val locationPreciseEnabledFlow: StateFlow<Boolean>,
) : DeviceTool {

  private companion object {
    val JsonParser = Json { ignoreUnknownKeys = true }
  }

  override val name = "location_get"

  override val description =
    "Get the current device location (GPS/network). " +
    "Returns latitude, longitude, accuracy in meters, and optionally altitude, speed, heading."

  override val parametersSchema: JsonObject = buildJsonObject {
    put("type", "object")
    put(
      "properties",
      buildJsonObject {
        put(
          "desiredAccuracy",
          buildJsonObject {
            put("type", "string")
            put("description", "Location accuracy: 'coarse' (city-level), 'balanced' (block-level), or 'precise' (GPS). Default: balanced")
          },
        )
        put(
          "maxAgeMs",
          buildJsonObject {
            put("type", "integer")
            put("description", "Accept a cached location if it is no older than this many milliseconds. Omit to always request fresh.")
          },
        )
        put(
          "timeoutMs",
          buildJsonObject {
            put("type", "integer")
            put("description", "Maximum wait time in milliseconds. Default: 15000")
          },
        )
      },
    )
  }

  override fun isAvailable(): Boolean = locationModeFlow.value != LocationMode.Off

  override suspend fun execute(params: JsonObject): ToolResult {
    val desiredAccuracy = params["desiredAccuracy"]?.jsonPrimitive?.contentOrNull?.trim() ?: "balanced"
    val maxAgeMs = params["maxAgeMs"]?.jsonPrimitive?.longOrNull
    val timeoutMs = params["timeoutMs"]?.jsonPrimitive?.longOrNull ?: 15_000L

    val isPrecise = desiredAccuracy == "precise" && locationPreciseEnabledFlow.value
    val providers = when (desiredAccuracy) {
      "precise" -> listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
      "coarse" -> listOf(LocationManager.NETWORK_PROVIDER)
      else -> listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
    }

    return try {
      val result = location.getLocation(
        desiredProviders = providers,
        maxAgeMs = maxAgeMs,
        timeoutMs = timeoutMs,
        isPrecise = isPrecise,
      )
      // LocationCaptureManager.Payload.payloadJson is already a well-formed JSON object
      val data = JsonParser
        .parseToJsonElement(result.payloadJson)

      ToolResult.ok(data = data)
    } catch (e: Throwable) {
      ToolResult.error("location_get failed: ${e.message ?: e.toString()}")
    }
  }
}
