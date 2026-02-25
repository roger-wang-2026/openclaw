package ai.openclaw.android.tools.edge

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ToolRegistryTest {

  private lateinit var registry: ToolRegistry

  /** A simple test tool that is always available. */
  private val echoTool = object : DeviceTool {
    override val name = "echo"
    override val description = "Echoes back the input."
    override val parametersSchema = buildJsonObject {
      put("type", "object")
      put("properties", buildJsonObject {
        put("text", buildJsonObject {
          put("type", "string")
          put("description", "Text to echo")
        })
      })
    }
    override fun isAvailable() = true
    override suspend fun execute(params: JsonObject): ToolResult {
      val text = params["text"]?.jsonPrimitive?.content ?: ""
      return ToolResult.ok(data = JsonPrimitive("echo: $text"))
    }
  }

  /** A test tool that is NOT available. */
  private val offlineTool = object : DeviceTool {
    override val name = "offline_sensor"
    override val description = "A sensor that is offline."
    override val parametersSchema = buildJsonObject { put("type", "object") }
    override fun isAvailable() = false
    override suspend fun execute(params: JsonObject): ToolResult {
      return ToolResult.error("should never be called")
    }
  }

  @Before
  fun setUp() {
    registry = ToolRegistry()
  }

  @Test
  fun `register and find tool`() {
    registry.register(echoTool)
    assertEquals(echoTool, registry.findTool("echo"))
    assertTrue(registry.registeredNames().contains("echo"))
  }

  @Test
  fun `find nonexistent tool returns null`() {
    assertNull(registry.findTool("nonexistent"))
  }

  @Test
  fun `unregister removes tool`() {
    registry.register(echoTool)
    registry.unregister("echo")
    assertNull(registry.findTool("echo"))
    assertTrue(registry.registeredNames().isEmpty())
  }

  @Test
  fun `availableTools filters out unavailable tools`() {
    registry.register(echoTool)
    registry.register(offlineTool)
    val available = registry.availableTools()
    assertEquals(1, available.size)
    assertEquals("echo", available[0].name)
  }

  @Test
  fun `toToolDefinitionsJson generates correct format`() {
    registry.register(echoTool)
    val jsonArray = registry.toToolDefinitionsJson()
    assertEquals(1, jsonArray.size)

    val entry = jsonArray[0].jsonObject
    assertEquals("function", entry["type"]?.jsonPrimitive?.content)

    val fn = entry["function"]?.jsonObject
    assertNotNull(fn)
    assertEquals("echo", fn!!["name"]?.jsonPrimitive?.content)
    assertEquals("Echoes back the input.", fn["description"]?.jsonPrimitive?.content)
    assertNotNull(fn["parameters"]?.jsonObject)
  }

  @Test
  fun `toToolDefinitionsJson excludes unavailable tools`() {
    registry.register(echoTool)
    registry.register(offlineTool)
    val jsonArray = registry.toToolDefinitionsJson()
    assertEquals(1, jsonArray.size)
    assertEquals(
      "echo",
      jsonArray[0].jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content,
    )
  }

  @Test
  fun `toToolDefinitionsJson returns empty array when no tools`() {
    val jsonArray = registry.toToolDefinitionsJson()
    assertEquals(0, jsonArray.size)
  }

  @Test
  fun `toSystemPromptBlock includes tool names and descriptions`() {
    registry.register(echoTool)
    val block = registry.toSystemPromptBlock()
    assertTrue(block.contains("echo"))
    assertTrue(block.contains("Echoes back the input."))
  }

  @Test
  fun `toSystemPromptBlock returns empty string when no tools available`() {
    registry.register(offlineTool)
    val block = registry.toSystemPromptBlock()
    assertEquals("", block)
  }

  @Test
  fun `registering tool with same name replaces it`() {
    registry.register(echoTool)
    val replacement = object : DeviceTool {
      override val name = "echo"
      override val description = "Replaced echo."
      override val parametersSchema = buildJsonObject { put("type", "object") }
      override fun isAvailable() = true
      override suspend fun execute(params: JsonObject) = ToolResult.ok(JsonNull)
    }
    registry.register(replacement)
    assertEquals(1, registry.registeredNames().size)
    assertEquals("Replaced echo.", registry.findTool("echo")?.description)
  }
}
