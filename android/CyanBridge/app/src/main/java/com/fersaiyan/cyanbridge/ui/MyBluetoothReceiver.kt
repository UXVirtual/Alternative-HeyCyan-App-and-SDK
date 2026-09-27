package com.fersaiyan.cyanbridge.ui
import android.bluetooth.BluetoothDevice
import android.nfc.Tag
import android.os.SystemClock
import android.os.UserManager
import android.util.Log
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.DeviceManager
import com.oudmon.ble.base.bluetooth.QCBluetoothCallbackCloneReceiver
import com.oudmon.ble.base.communication.Constants
import com.oudmon.ble.base.communication.LargeDataHandler
import com.fersaiyan.cyanbridge.devices.DeviceProfileStore
import org.greenrobot.eventbus.EventBus

/** Process-local observation of vendor BLE setup; does not change the SDK connection state. */
object HeyCyanBleSetupTrace {
    data class Snapshot(
        val sequence: Long,
        val lastEventAtMs: Long,
        val connectionEvents: Int,
        val discoveryEvents: Int,
        val lastEvent: String,
    )

    private var sequence = 0L
    private var lastEventAtMs = 0L
    private var connectionEvents = 0
    private var discoveryEvents = 0
    private var lastEvent = "none"

    @Synchronized
    fun connection(connected: Boolean) {
        connectionEvents++
        record("connection:$connected")
    }

    @Synchronized
    fun discovered() {
        discoveryEvents++
        record("servicesDiscovered")
    }

    private fun record(event: String) {
        lastEventAtMs = SystemClock.elapsedRealtime()
        lastEvent = event
        sequence++
        Log.i("HeyCyanBleSetupTrace", "event=$event sequence=$sequence connectedEvents=$connectionEvents discoveryEvents=$discoveryEvents")
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(sequence, lastEventAtMs, connectionEvents, discoveryEvents, lastEvent)
}

/**
 * @author hzy ,
 * @date  2021/1/15
 * <p>
 * "Programs should be written for other people to read,
 * and only incidentally for machines to execute"
 **/
class MyBluetoothReceiver : QCBluetoothCallbackCloneReceiver() {
    override fun connectStatue(device: BluetoothDevice?, connected: Boolean) {
        if (!DeviceProfileStore.isMetaSelected(MyApplication.getInstance())) {
            HeyCyanBleSetupTrace.connection(connected)
        }
        val address = device?.address ?: "unknown"
        val name = try {
            device?.name ?: "unknown"
        } catch (_: SecurityException) {
            "unknown"
        }
        val sdkDeviceName = try { DeviceManager.getInstance().deviceName ?: "unknown" } catch (_: Exception) { "unknown" }
        val sdkDeviceAddress = try { DeviceManager.getInstance().deviceAddress ?: "unknown" } catch (_: Exception) { "unknown" }
        Log.i(
            "MyBluetoothReceiver",
            "connectStatue connected=$connected device=$address name=$name sdkDeviceName=$sdkDeviceName sdkDeviceAddress=$sdkDeviceAddress selectedClass=${DeviceProfileStore.selectedClass(MyApplication.getInstance())} isReady=${BleOperateManager.getInstance().isReady}",
        )
        if (DeviceProfileStore.isMetaSelected(MyApplication.getInstance())) return
        if (device != null && connected) {
            val deviceName = try {
                device.name
            } catch (_: SecurityException) {
                null
            }
            if (deviceName != null) {
                DeviceManager.getInstance().deviceName = deviceName
            }
        } else {
            EventBus.getDefault().post(BluetoothEvent(false))
        }
    }

    override fun onServiceDiscovered() {
        if (DeviceProfileStore.isMetaSelected(MyApplication.getInstance())) return
        HeyCyanBleSetupTrace.discovered()
        //do init
        LargeDataHandler.getInstance().initEnable()
        // Must receive a callback before other instructions can be issued
        // eg. set time, sync settings, etc.
        EventBus.getDefault().post(BluetoothEvent(true))
        Log.i(
            "MyBluetoothReceiver",
            "onServiceDiscovered isReady=${BleOperateManager.getInstance().isReady} deviceName=${try { DeviceManager.getInstance().deviceName ?: "unknown" } catch (_: Exception) { "unknown" }} deviceAddress=${try { DeviceManager.getInstance().deviceAddress ?: "unknown" } catch (_: Exception) { "unknown" }}",
        )
        BleOperateManager.getInstance().isReady=true
    }

    override fun onCharacteristicChange(address: String?, uuid: String?, data: ByteArray?) {
        if (data != null) {
            // Feed all notifications into BleIpBridge so it can try to
            // detect any IP information broadcast over BLE.
            bleIpBridge.onCharacteristicChanged("notify:$uuid", data)
        }
    }

    override fun onCharacteristicRead(uuid: String?, data: ByteArray?) {
        if (uuid != null && data != null) {
            val version = String(data, Charsets.UTF_8)
            when(uuid){
                Constants.CHAR_FIRMWARE_REVISION.toString() -> {
                    Log.e("rom----", version)
                    // rom version
                    MyApplication.getInstance().firmwareVersion = version
                }
                Constants.CHAR_HW_REVISION.toString() -> {
                    // hardware version
                    Log.e("hardware----", version)
                    MyApplication.getInstance().hardwareVersion = version
                }
                else -> {
                    // Also send any other characteristic reads through BleIpBridge
                    bleIpBridge.onCharacteristicChanged("read:$uuid", data)
                }
            }
        }
    }


}
