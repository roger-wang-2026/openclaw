package ai.openclaw.android.tools.edge

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ToolDispatcherTest {

  private lateinit var registry: ToolRegistry
  private lateinit var dispatcher: ToolDispatcher

  /** Simple tool that returns whatever was passed as "input". */
  private val mirrorTool = object : DeviceTool {
    override val name = "mirror"
    override val description = "Returns the input back."
    override val parametersSchema = buildJsonObject {
      put("type", "object")
      put("properties", buildJsonObject {
        put("input", buildJsonObject { put("type", "string") })
      })
    }
    override fun isAvailable() = true
    override suspend fun execute(params: JsonObject): ToolResult {
      val input = params["input"]?.jsonPrimitive?.content ?: "empty"
      return ToolResult.ok(data = JsonPrimitive(input))
    }
  }

  /** Tool that is marked unavailable. */
  private val unavailableTool = object : DeviceTool {
    override val name = "broken"
    override val description = "Always unavailable."
    override val parametersSchema = buildJsonObject { put("type", "object") }
    override fun isAvailable() = false
    override suspend fun execute(params: JsonObject) = ToolResult.error("unreachable")
  }

  /** Tool that always throws. */
  private val crashingTool = object : DeviceTool {
    override val name = "crasher"
    override val description = "Always throws."
    override val parametersSchema = buildJsonObject { put("type", "object") }
    override fun isAvailable() = true
    override suspend fun execute(params: JsonObject): ToolResult {
      throw RuntimeException("simulated crash")
    }
  }

  @Before
  fun setUp() {
    registry = ToolRegistry()
    registry.register(mirrorTool)
    registry.register(unavailableTool)
    registry.register(crashingTool)
    dispatcher = ToolDispatcher(registry)
  }

  @Test
  fun `dispatch routes to correct tool and returns result`() = runBlocking {
    val call = buildJsonObject {
      put("name", "mirror")
      put("arguments", buildJsonObject { put("input", "hello") })
    }
    val result = dispatcher.dispatch(call)
    assertTrue(result.success)
    assertEquals("hello", result.data.jsonPrimitive.content)
  }

  @Test
  fun `dispatch with string-encoded arguments`() = runBlocking {
    val call = buildJsonObject {
      put("name", "mirror")
      put("arguments", """{"input":"from string"}""")
    }
    val result = dispatcher.dispatch(call)
    assertTrue(result.success)
    assertEquals("from string", result.data.jsonPrimitive.content)
  }

  @Test
  fun `dispatch with missing name returns error`() = runBlocking {
    val call = buildJsonObject {
      put("arguments", buildJsonObject { put("input", "test") })
    }
    val result = dispatcher.dispatch(call)
    assertFalse(result.success)
    assertTrue(result.error!!.contains("missing"))
  }

  @Test
  fun `dispatch with unknown tool returns error`() = runBlocking {
    val call = buildJsonObject {
      put("name", "nonexistent")
      put("arguments", "{}")
    }
    val result = dispatcher.dispatch(call)
    assertFalse(result.success)
    assertTrue(result.error!!.contains("unknown tool"))
  }

  @Test
  fun `dispatch with unavailable tool returns error`() = runBlocking {
    val call = buildJsonObject {
      put("name", "broken")
      put("arguments", "{}")
    }
    val result = dispatcher.dispatch(call)
    assertFalse(result.success)
    assertTrue(result.error!!.contains("not currently available"))
  }

  @Test
  fun `dispatch catches tool exceptions`() = runBlocking {
    val call = buildJsonObject {
      put("name", "crasher")
      put("arguments", "{}")
    }
    val result = dispatcher.dispatch(call)
    assertFalse(result.success)
    assertTrue(result.error!!.contains("simulated crash"))
  }

  @Test
  fun `dispatch with empty arguments defaults to empty object`() = runBlocking {
    val call = buildJsonObject {
      put("name", "mirror")
    }
    val result = dispatcher.dispatch(call)
    assertTrue(result.success)
    // No "input" key → default "empty"
    assertEquals("empty", result.data.jsonPrimitive.content)
  }

  @Test
  fun `dispatch with invalid JSON arguments returns error`() = runBlocking {
    val call = buildJsonObject {
      put("name", "mirror")
      put("arguments", "not valid json{{{")
    }
    val result = dispatcher.dispatch(call)
    assertFalse(result.success)
    assertTrue(result.error!!.contains("invalid arguments"))
  }

  @Test
  fun `dispatch with empty string arguments works`() = runBlocking {
    val call = buildJsonObject {
      put("name", "mirror")
      put("arguments", "")
    }
    val result = dispatcher.dispatch(call)
    assertTrue(result.success)
    assertEquals("empty", result.data.jsonPrimitive.content)
  }
}
