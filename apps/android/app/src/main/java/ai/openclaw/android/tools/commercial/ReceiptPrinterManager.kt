package ai.openclaw.android.tools.commercial

/**
 * Result of a print operation.
 */
data class PrintResult(
  val ok: Boolean,
  val error: String? = null,
)

/**
 * Abstract receipt printer interface.
 *
 * Implementations wrap vendor-specific SDKs (Centerm, Sunmi, etc.).
 * Replace [StubReceiptPrinterManager] with the real SDK adapter.
 */
interface ReceiptPrinterManager {
  /** Whether the printer hardware is present and ready. */
  fun isAvailable(): Boolean

  /** Print a text receipt. */
  suspend fun printText(text: String): PrintResult

  /**
   * Print an image receipt from a base64-encoded bitmap.
   *
   * @param base64 Base64-encoded image data (PNG or JPEG)
   */
  suspend fun printImage(base64: String): PrintResult
}

/**
 * Stub implementation for testing without real printer hardware.
 */
class StubReceiptPrinterManager : ReceiptPrinterManager {
  override fun isAvailable(): Boolean = true

  override suspend fun printText(text: String): PrintResult =
    PrintResult(ok = true)

  override suspend fun printImage(base64: String): PrintResult =
    PrintResult(ok = true)
}
