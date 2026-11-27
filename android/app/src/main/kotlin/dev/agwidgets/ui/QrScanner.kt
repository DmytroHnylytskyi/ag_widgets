package dev.agwidgets.ui

import android.content.Context
import android.util.Log
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/** Outcome of a single QR scan attempt. */
internal sealed interface QrScanResult {
    data class Success(val rawValue: String) : QrScanResult
    data object Cancelled : QrScanResult
    data class Unavailable(val reason: String?) : QrScanResult
}

/**
 * Trims a scanned payload and rejects blanks or implausibly long strings.
 *
 * Length is checked *after* trimming so trailing whitespace from a sloppy
 * generator can never push a valid code over the limit.
 */
internal fun normalizeScannedPayload(raw: String?): String? {
    val trimmed = raw?.trim() ?: return null
    if (trimmed.isEmpty()) return null
    if (trimmed.length > QrScanner.MAX_PAYLOAD_LENGTH) return null
    return trimmed
}

/**
 * Google Play Services code scanner (ML Kit "barcode UI").
 *
 * The Play Services scanner runs in a separate process and returns only the
 * decoded string, so the app never needs the CAMERA permission and never
 * touches a camera preview of its own.
 */
object QrScanner {
    const val MAX_PAYLOAD_LENGTH = 2048

    private const val TAG = "QrScanner"

    /**
     * Opens the Play Services scanner UI and reports [onResult] exactly once.
     *
     * `internal` because [QrScanResult] is module-private: the app has no
     * library consumers, so nothing needs this exposed.
     */
    internal fun scanQr(context: Context, onResult: (QrScanResult) -> Unit) {
        var reported = false
        fun report(result: QrScanResult) {
            if (reported) return
            reported = true
            runCatching { onResult(result) }
                .onFailure { Log.w(TAG, "QR result callback failed", it) }
        }

        val started = runCatching {
            val options = GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .enableAutoZoom()
                .build()
            val scanner = GmsBarcodeScanning.getClient(context, options)
            scanner.startScan()
                .addOnSuccessListener { barcode ->
                    val value = normalizeScannedPayload(barcode.rawValue)
                    if (value != null) {
                        report(QrScanResult.Success(value))
                    } else {
                        report(QrScanResult.Unavailable(null))
                    }
                }
                .addOnCanceledListener { report(QrScanResult.Cancelled) }
                .addOnFailureListener { error ->
                    report(QrScanResult.Unavailable(error.localizedMessage ?: error.message))
                }
        }
        // Devices without Google Play Services (or a broken ML Kit install)
        // throw while building the client; that is a graceful fallback, not a crash.
        started.onFailure { error ->
            Log.w(TAG, "QR scanner unavailable", error)
            report(QrScanResult.Unavailable(error.message))
        }
    }
}
