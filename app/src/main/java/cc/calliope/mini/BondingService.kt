package cc.calliope.mini

import android.Manifest
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import cc.calliope.mini.service.DfuControlService
import cc.calliope.mini.service.GattStatus
import cc.calliope.mini.utils.BluetoothUtils
import cc.calliope.mini.utils.Preference
import cc.calliope.mini.utils.StaticExtras
import cc.calliope.mini.utils.Utils
import cc.calliope.mini.utils.Version
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

open class BondingService : Service() {
    companion object {
        const val TAG = "BondingService"
        const val GATT_DISCONNECTED_BY_DEVICE = 19
        const val EXTRA_DEVICE_ADDRESS = StaticExtras.CURRENT_DEVICE_ADDRESS
        const val EXTRA_DEVICE_VERSION = StaticExtras.CURRENT_DEVICE_VERSION

        private val DFU_CONTROL_SERVICE_UUID =
            UUID.fromString("E95D93B0-251D-470A-A062-FA1922DFA9A8")
        private val DFU_CONTROL_CHARACTERISTIC_UUID =
            UUID.fromString("E95D93B1-251D-470A-A062-FA1922DFA9A8")
        private val SECURE_DFU_SERVICE_UUID =
            UUID.fromString("0000FE59-0000-1000-8000-00805F9B34FB")
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private var deviceVersion: Int = DfuControlService.UNIDENTIFIED

    @SuppressWarnings("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Utils.log(Log.DEBUG, TAG, "onConnectionStateChange: $newState")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> handleConnectedState(gatt)
                    BluetoothProfile.STATE_DISCONNECTED -> stopService(gatt)
                }
            } else if (status == GATT_DISCONNECTED_BY_DEVICE){
                Utils.log(Log.WARN, TAG, "Disconnected by device")
                reConnect(gatt.device.address)
            } else {
                val message: String = getString(GattStatus.get(status).message)
                Utils.log(Log.ERROR, TAG, "Error: $status $message")
                stopService(gatt)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Utils.log(Log.DEBUG, TAG, "onServicesDiscovered: $status")

            if (status == BluetoothGatt.GATT_SUCCESS) {
                getDfuControlService(gatt)
            } else {
                gatt.disconnect()
                Utils.log(Log.WARN, TAG, "Services discovered not success")
            }
        }

        @Suppress("DEPRECATION")
        @Deprecated(
            "Used natively in Android 12 and lower",
            ReplaceWith("onCharacteristicRead(gatt, characteristic, characteristic.value, status)")
        )
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            onCharacteristicRead(gatt, characteristic, characteristic.value, status)
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Utils.log(Log.DEBUG, TAG, "Characteristic read: ${characteristic.uuid} Value: ${value.let { it.contentToString() }}")
            } else {
                Utils.log(Log.WARN, TAG, "Characteristic read failed: ${characteristic.uuid}")
            }
            gatt.disconnect()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Utils.log(Log.DEBUG, TAG, "Service created")
    }

    override fun onBind(intent: Intent): IBinder? {
        // TODO: Return the communication channel to the service.
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Utils.log(TAG, "Bonding Service started")

        if (Version.VERSION_S_AND_NEWER
            && ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            && ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) {
                Utils.log(Log.ERROR, TAG, "BLUETOOTH permission no granted")
                stopSelf()
                return START_NOT_STICKY
        }

        val address = intent?.getStringExtra(EXTRA_DEVICE_ADDRESS)
        connect(address)

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Utils.log(TAG, "Bonding Service destroyed")
        Preference.putInt(applicationContext, StaticExtras.CURRENT_DEVICE_VERSION, deviceVersion)
        Utils.log(Log.DEBUG, TAG, "Device version: $deviceVersion")
        serviceJob.cancel()
    }

    private fun reConnect(address: String?) {
        Utils.log(Log.DEBUG, TAG, "Reconnecting to the device...")
        serviceScope.launch {
            delay(2000)
            connect(address)
        }
    }

    @SuppressWarnings("MissingPermission")
    private fun connect(address: String?) {
        Utils.log(Log.DEBUG, TAG, "Connecting to the device...")

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter? = bluetoothManager.adapter

        if (adapter == null || !adapter.isEnabled || !BluetoothUtils.isValidBluetoothMAC(address)) {
            stopSelf()
            return
        }

        val device = adapter.getRemoteDevice(address)
        if (device == null) {
            Utils.log(Log.ERROR, TAG, "Device is null")
            stopSelf()
            return
        }

        if (Version.VERSION_O_AND_NEWER) {
            device.connectGatt(this, false,
                gattCallback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK or BluetoothDevice.PHY_LE_2M_MASK)
        } else if (Version.VERSION_M_AND_NEWER) {
            device.connectGatt(this, false,
                gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(this, false,
                gattCallback)
        }
    }

    @SuppressWarnings("MissingPermission")
    private fun handleConnectedState(gatt: BluetoothGatt) {
        val bondState = gatt.device.bondState
        if (bondState == BluetoothDevice.BOND_BONDING) {
            Utils.log(Log.WARN, TAG, "Waiting for bonding to complete")
        } else {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N && bondState == BluetoothDevice.BOND_BONDED) {
                serviceScope.launch {
                    Utils.log(Log.DEBUG, TAG, "Wait for 1600 millis before service discovery")
                    delay(1600)
                    startServiceDiscovery(gatt)
                }
            } else {
                startServiceDiscovery(gatt)
            }
        }
    }

    @SuppressWarnings("MissingPermission")
    private fun startServiceDiscovery(gatt: BluetoothGatt) {
        val result = gatt.discoverServices()
        if (!result) {
            Utils.log(Log.ERROR, TAG, "discoverServices failed to start")
        }
    }

    @SuppressWarnings("MissingPermission")
    fun getDfuControlService(gatt: BluetoothGatt) {
        val dfuControlService = gatt.getService(DFU_CONTROL_SERVICE_UUID)
        if (dfuControlService == null) {
            Utils.log(Log.WARN, TAG, "Cannot find DEVICE_FIRMWARE_UPDATE_CONTROL_SERVICE_UUID")
            getSecureDfuService(gatt)
            return
        }

        val dfuControlCharacteristic = dfuControlService.getCharacteristic(DFU_CONTROL_CHARACTERISTIC_UUID)
        if (dfuControlCharacteristic == null) {
            Utils.log(Log.WARN, TAG, "Cannot find DEVICE_FIRMWARE_UPDATE_CONTROL_CHARACTERISTIC_UUID")
            gatt.disconnect()
            return
        }
        deviceVersion = DfuControlService.MINI_V1
        gatt.readCharacteristic(dfuControlCharacteristic)
    }

    @SuppressWarnings("MissingPermission")
    fun getSecureDfuService(gatt: BluetoothGatt) {
        val secureDfuService = gatt.getService(SECURE_DFU_SERVICE_UUID)
        if (secureDfuService == null) {
            Utils.log(Log.WARN, TAG, "Cannot find SECURE_DFU_SERVICE_UUID")
            gatt.disconnect()
            return
        }

        deviceVersion = DfuControlService.MINI_V2
        gatt.disconnect()
    }

    @SuppressWarnings("MissingPermission")
    private fun stopService(gatt: BluetoothGatt) {
        BluetoothUtils.clearServicesCache(gatt)
        gatt.close()
        stopSelf()
    }
}
