package dev.snapecg.holter.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log

/**
 * Resolves the SnapECG B10's Bluetooth MAC address.
 *
 * Resolution order:
 *   1. `snapecg_bt_address` in SharedPreferences (set by the user, persists
 *      across launches once a recording session has succeeded).
 *   2. Auto-detect: scan bonded (paired) Bluetooth devices and pick the first
 *      one whose name matches the SnapECG/B10 pattern.
 *   3. Fall back to the historical hard-coded MAC of the developer's device
 *      (`LEGACY_DEFAULT_BT_ADDRESS`) so existing installs keep working.
 *
 * Whichever step succeeds, the resolved address is also written back to
 * SharedPreferences so step 1 will succeed on subsequent launches.
 */
object DeviceResolver {

    private const val TAG = "DeviceResolver"
    private const val PREFS = "snapecg"
    private const val PREF_KEY = "snapecg_bt_address"

    /**
     * Last-resort fallback. Replaced by user's actual device MAC the first
     * time auto-detect succeeds and is then persisted.
     */
    const val LEGACY_DEFAULT_BT_ADDRESS = "34:81:F4:1C:3F:C1"

    private val NAME_PATTERNS = listOf("snapecg", "b10")

    @SuppressLint("MissingPermission")
    fun resolve(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(PREF_KEY, null)?.takeIf { it.isNotBlank() }?.let {
            Log.d(TAG, "Resolved BT address from prefs: $it")
            return it
        }

        // Try paired devices.
        try {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = manager?.adapter
            val match = adapter?.bondedDevices?.firstOrNull { dev ->
                val name = dev.name?.lowercase() ?: return@firstOrNull false
                NAME_PATTERNS.any { it in name }
            }
            if (match != null) {
                Log.i(TAG, "Auto-detected paired device: ${match.name} (${match.address})")
                save(context, match.address)
                return match.address
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Bluetooth permission missing; cannot scan paired devices: ${e.message}")
        }

        Log.w(TAG, "No paired SnapECG device found; using legacy default $LEGACY_DEFAULT_BT_ADDRESS")
        return LEGACY_DEFAULT_BT_ADDRESS
    }

    fun save(context: Context, address: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(PREF_KEY, address).apply()
    }
}
