package ai.openclaw.android.tools.commercial

/**
 * Result of an NFC/IC card read operation.
 */
data class CardResult(
  val ok: Boolean,
  /** Card UID (hex string, e.g. "A1B2C3D4"). */
  val uid: String? = null,
  /** Card type: "MIFARE_CLASSIC", "MIFARE_ULTRALIGHT", "ISO_DEP", etc. */
  val cardType: String? = null,
  /** Optional extra data read from the card (hex or text). */
  val data: String? = null,
  val error: String? = null,
)

/**
 * Abstract NFC/IC card reader interface.
 *
 * Implementations wrap vendor-specific NFC modules or Android's
 * built-in NFC adapter. Replace [StubNfcCardReaderManager] with
 * the real SDK adapter.
 */
interface NfcCardReaderManager {
  /** Whether the NFC reader is present and enabled. */
  fun isAvailable(): Boolean

  /**
   * Wait for a card tap and read its UID and basic info.
   *
   * @param timeoutMs Maximum wait time in milliseconds
   * @return The card read result containing UID and type
   */
  suspend fun readCard(timeoutMs: Long = 15_000): CardResult
}

/**
 * Stub implementation for testing without real NFC hardware.
 */
class StubNfcCardReaderManager : NfcCardReaderManager {
  var nextResult: CardResult = CardResult(
    ok = true,
    uid = "A1B2C3D4",
    cardType = "MIFARE_CLASSIC",
  )

  override fun isAvailable(): Boolean = true

  override suspend fun readCard(timeoutMs: Long): CardResult = nextResult
}
