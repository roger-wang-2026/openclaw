package ai.openclaw.android.tools.commercial

/**
 * Result of a barcode scan operation.
 */
data class ScanResult(
  val ok: Boolean,
  /** The scanned barcode/QR content (null on failure). */
  val content: String? = null,
  /** Barcode symbology: CODE_128, QR_CODE, EAN_13, etc. */
  val format: String? = null,
  val error: String? = null,
)

/**
 * Abstract barcode scanner interface.
 *
 * Implementations wrap vendor-specific scanner SDKs (hardware scan head)
 * or CameraX-based software decoding.
 * Replace [StubBarcodeScannerManager] with the real SDK adapter.
 */
interface BarcodeScannerManager {
  /** Whether the scanner hardware is present and ready. */
  fun isAvailable(): Boolean

  /**
   * Start a scan and wait for a result.
   *
   * @param timeoutMs Maximum wait time in milliseconds
   * @return The scan result containing barcode content and format
   */
  suspend fun scan(timeoutMs: Long = 10_000): ScanResult
}

/**
 * Stub implementation for testing without real scanner hardware.
 */
class StubBarcodeScannerManager : BarcodeScannerManager {
  var nextResult: ScanResult = ScanResult(
    ok = true,
    content = "6901234567890",
    format = "EAN_13",
  )

  override fun isAvailable(): Boolean = true

  override suspend fun scan(timeoutMs: Long): ScanResult = nextResult
}
