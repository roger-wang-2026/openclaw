package ai.openclaw.android.tools.edge

import ai.openclaw.android.LocationMode
import ai.openclaw.android.node.CameraCaptureManager
import ai.openclaw.android.node.LocationCaptureManager
import ai.openclaw.android.node.ScreenRecordManager
import ai.openclaw.android.node.SmsManager
import ai.openclaw.android.tools.commercial.BarcodeScannerManager
import ai.openclaw.android.tools.commercial.NfcCardReaderManager
import ai.openclaw.android.tools.commercial.ReceiptPrinterManager
import kotlinx.coroutines.flow.StateFlow

/**
 * Centralized initialization of the edge tool framework.
 *
 * Wires together all hardware managers (already created by NodeRuntime) into:
 * - A [ToolRegistry] with all registered [DeviceTool]s
 * - A [ToolDispatcher] for routing function calls
 * - An [EdgeModelChat] session manager for multi-turn tool-calling
 *
 * Commercial device managers (printer, scanner, NFC) are optional — pass
 * them only on devices that have the corresponding hardware.
 *
 * Create this once (typically from [ai.openclaw.android.NodeRuntime]) and
 * share its [edgeChat] reference wherever local inference is needed.
 */
class EdgeToolModule(
  camera: CameraCaptureManager,
  location: LocationCaptureManager,
  smsManager: SmsManager,
  screenRecorder: ScreenRecordManager,
  cameraEnabledFlow: StateFlow<Boolean>,
  locationModeFlow: StateFlow<LocationMode>,
  locationPreciseEnabledFlow: StateFlow<Boolean>,
  screenRecordActiveFlow: StateFlow<Boolean>,
  engine: InferenceEngine = StubInferenceEngine(),
  // Commercial device managers (optional)
  printer: ReceiptPrinterManager? = null,
  barcodeScanner: BarcodeScannerManager? = null,
  nfcReader: NfcCardReaderManager? = null,
) {
  val registry = ToolRegistry()
  val dispatcher: ToolDispatcher
  var edgeChat: EdgeModelChat
    private set

  /** Whether the inference engine is loaded and ready. */
  val isReady: Boolean get() = edgeChat.let { true }

  init {
    // Register consumer hardware tools
    registry.register(CameraSnapTool(camera, cameraEnabledFlow))
    registry.register(LocationTool(location, locationModeFlow, locationPreciseEnabledFlow))
    registry.register(SmsTool(smsManager))
    registry.register(ScreenCaptureTool(screenRecorder, screenRecordActiveFlow))

    // Register commercial device tools (when hardware is available)
    if (printer != null) registry.register(ReceiptPrinterTool(printer))
    if (barcodeScanner != null) registry.register(BarcodeScannerTool(barcodeScanner))
    if (nfcReader != null) registry.register(NfcCardReaderTool(nfcReader))

    dispatcher = ToolDispatcher(registry)
    edgeChat = EdgeModelChat(
      engine = engine,
      registry = registry,
      dispatcher = dispatcher,
    )
  }

  /**
   * Replace the inference engine (e.g. after loading a real llama.cpp model).
   *
   * Creates a new [EdgeModelChat] backed by the given engine while
   * reusing the existing [ToolRegistry] and [ToolDispatcher].
   * Returns the new [EdgeModelChat] for convenience.
   */
  fun replaceEngine(engine: InferenceEngine): EdgeModelChat {
    edgeChat = EdgeModelChat(
      engine = engine,
      registry = registry,
      dispatcher = dispatcher,
    )
    return edgeChat
  }
}
