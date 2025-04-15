package com.example.bleheartrateapp

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var heartRateTextView: TextView
    private lateinit var configEditText: EditText
    private lateinit var sendConfigButton: Button
    private lateinit var deviceListView: ListView

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var bleScanner: BluetoothLeScanner
    private var scanning = false
    private val scanResults = mutableListOf<ScanResult>()
    private lateinit var listAdapter: ArrayAdapter<String>

    private var bluetoothGatt: BluetoothGatt? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        heartRateTextView = findViewById(R.id.heartRateTextView)
        configEditText = findViewById(R.id.configEditText)
        sendConfigButton = findViewById(R.id.sendConfigButton)
        deviceListView = findViewById(R.id.deviceListView)

        // Inicializar Bluetooth
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Toast.makeText(this, "Bluetooth não disponível ou desligado", Toast.LENGTH_LONG).show()
            return
        }

        bleScanner = bluetoothAdapter.bluetoothLeScanner

        checkPermissions()

        // Configurar lista
        listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        deviceListView.adapter = listAdapter

        // Ao tocar em um dispositivo, conecta
        deviceListView.setOnItemClickListener { _, _, position, _ ->
            val device = scanResults[position].device

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Toast.makeText(this, "Permissão BLUETOOTH_CONNECT não concedida", Toast.LENGTH_SHORT).show()
                return@setOnItemClickListener
            }

            connectToDevice(device)
        }

        // Botão de enviar config
        sendConfigButton.setOnClickListener {
            val config = configEditText.text.toString()
            bluetoothGatt?.let { gatt ->
                val service = gatt.getService(UUID.fromString("0000180D-0000-1000-8000-00805f9b34fb"))
                val configCharacteristic = service?.characteristics?.firstOrNull {
                    it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
                }

                if (configCharacteristic != null) {
                    configCharacteristic.value = config.toByteArray()
                    gatt.writeCharacteristic(configCharacteristic)
                    Toast.makeText(this, "Enviado para ESP32!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Não foi possível enviar (sem characteristic de escrita)", Toast.LENGTH_SHORT).show()
                }
            } ?: run {
                Toast.makeText(this, "Não conectado ao ESP32", Toast.LENGTH_SHORT).show()
            }
        }

        // Iniciar scan BLE
        startBLEScan()
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), 1)
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    private fun startBLEScan() {
        if (scanning) return

        scanResults.clear()
        listAdapter.clear()
        scanning = true

        val scanCallback = object : ScanCallback() {
            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                if (!scanResults.any { it.device.address == device.address }) {
                    scanResults.add(result)
                    val name = device.name ?: "Dispositivo sem nome"
                    listAdapter.add("$name\n${device.address}")
                }
            }
        }

        bleScanner.startScan(scanCallback)

        // Parar o scan após 10 segundos
        Handler(Looper.getMainLooper()).postDelayed(@RequiresPermission(Manifest.permission.BLUETOOTH_SCAN) {
            bleScanner.stopScan(scanCallback)
            scanning = false
            Toast.makeText(this, "Scan BLE finalizado", Toast.LENGTH_SHORT).show()
        }, 10000)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun connectToDevice(device: BluetoothDevice) {
        Toast.makeText(this, "Conectando a: ${device.name ?: device.address}", Toast.LENGTH_SHORT).show()
        bluetoothGatt = device.connectGatt(this, false, gattCallback)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Conectado ao dispositivo", Toast.LENGTH_SHORT).show()
                }
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Desconectado do dispositivo", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val heartRateService = gatt.getService(UUID.fromString("0000180D-0000-1000-8000-00805f9b34fb"))
                val heartRateCharacteristic = heartRateService?.getCharacteristic(
                    UUID.fromString("00002A37-0000-1000-8000-00805f9b34fb")
                )

                if (heartRateCharacteristic != null) {
                    gatt.setCharacteristicNotification(heartRateCharacteristic, true)

                    val descriptor = heartRateCharacteristic.getDescriptor(
                        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                    )
                    descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)

                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Notificações ativadas!", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Characteristic de batimento não encontrada", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == UUID.fromString("00002A37-0000-1000-8000-00805f9b34fb")) {
                val flag = characteristic.properties
                val format = if (flag and 0x01 != 0) {
                    BluetoothGattCharacteristic.FORMAT_UINT16
                } else {
                    BluetoothGattCharacteristic.FORMAT_UINT8
                }

                val heartRate = characteristic.getIntValue(format, 1)
                runOnUiThread {
                    heartRateTextView.text = "Heart Rate: $heartRate bpm"
                }
            }
        }
    }
}
