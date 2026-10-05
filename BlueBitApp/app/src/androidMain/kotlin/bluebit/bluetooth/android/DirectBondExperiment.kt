package bluebit.bluetooth.android

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import kotlinx.coroutines.delay

/**
 * Experiment: Test direct Android createBond() on Inspire 3
 * WITHOUT GoldenGate/CoAP bond/control.
 *
 * Goal: Determine if bond/control is actually required for SMP pairing,
 * or if the device can bond via standard Android BLE.
 */
class DirectBondExperiment(private val context: Context) {

    companion object {
        private const val TAG = "BlueBitDirectBond"
    }

    private var receiver: BroadcastReceiver? = null
    private var bondStateLog = StringBuilder()

    suspend fun testDirectBond(device: BluetoothDevice) {
        Log.i(TAG, "========================================")
        Log.i(TAG, "DIRECT BOND EXPERIMENT START")
        Log.i(TAG, "Device: ${device.address}, name=${device.name}")
        Log.i(TAG, "Initial bondState: ${device.bondState}")
        bondStateLog.append("Start bondState=${device.bondState}\n")

        // Register bond state receiver BEFORE calling createBond
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val action = intent.action
                val dev = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (dev?.address != device.address) return

                when (action) {
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val prevState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)
                        val newState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                        val stateName = when (newState) {
                            BluetoothDevice.BOND_NONE -> "BOND_NONE"
                            BluetoothDevice.BOND_BONDING -> "BOND_BONDING"
                            BluetoothDevice.BOND_BONDED -> "BOND_BONDED"
                            else -> "UNKNOWN($newState)"
                        }
                        val prevName = when (prevState) {
                            BluetoothDevice.BOND_NONE -> "BOND_NONE"
                            BluetoothDevice.BOND_BONDING -> "BOND_BONDING"
                            BluetoothDevice.BOND_BONDED -> "BOND_BONDED"
                            else -> "UNKNOWN($prevState)"
                        }
                        Log.i(TAG, "BOND_STATE_CHANGED: $prevName → $stateName")
                        bondStateLog.append("BOND: $prevName → $stateName\n")
                    }
                    BluetoothDevice.ACTION_PAIRING_REQUEST -> {
                        val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
                        val variantName = when (variant) {
                            BluetoothDevice.PAIRING_VARIANT_PIN -> "PIN"
                            BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION -> "PASSKEY_CONFIRMATION"
                            1 -> "PASSKEY"
                            3 -> "CONSENT"
                            4 -> "DISPLAY_PASSKEY"
                            5 -> "DISPLAY_PIN"
                            6 -> "OOB_CONSENT"
                            else -> "UNKNOWN($variant)"
                        }
                        Log.i(TAG, "PAIRING_REQUEST: variant=$variantName")
                        bondStateLog.append("PAIRING: variant=$variantName\n")
                    }
                }
            }
        }

        context.registerReceiver(receiver, filter)

        try {
            Log.i(TAG, "Calling device.createBond()...")
            val result = device.createBond()
            Log.i(TAG, "createBond() returned: $result")
            bondStateLog.append("createBond() returned=$result\n")

            // Wait up to 60 seconds for bonding to complete
            Log.i(TAG, "Waiting up to 60s for bond state changes...")
            repeat(60) { i ->
                delay(1000)
                val currentState = device.bondState
                val stateName = when (currentState) {
                    BluetoothDevice.BOND_NONE -> "BOND_NONE"
                    BluetoothDevice.BOND_BONDING -> "BOND_BONDING"
                    BluetoothDevice.BOND_BONDED -> "BOND_BONDED"
                    else -> "UNKNOWN"
                }
                Log.i(TAG, "Poll ${i+1}s: bondState=$stateName")
                if (currentState == BluetoothDevice.BOND_BONDED) {
                    Log.i(TAG, "SUCCESS: Device bonded!")
                    bondStateLog.append("SUCCESS: BONDED\n")
                    return
                }
            }

            Log.i(TAG, "TIMEOUT: Bonding did not complete within 60s")
            bondStateLog.append("TIMEOUT after 60s\n")

        } finally {
            receiver?.let { context.unregisterReceiver(it) }
            receiver = null
            Log.i(TAG, "DIRECT BOND EXPERIMENT END")
            Log.i(TAG, "Bond log:\n$bondStateLog")
        }
    }
}
