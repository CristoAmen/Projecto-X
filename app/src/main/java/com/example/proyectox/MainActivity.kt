package com.example.proyectox

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.DialogInterface
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.example.proyectox.R
import java.util.UUID

class MainActivity : AppCompatActivity() {

    companion object {
        // Etiqueta para los logs
        private const val ETIQUETA = "MainActivity"
    }

    // UUIDs para el Arduino R4 (asegúrate de que coincidan con tu configuración)
    private val uuidServicio: UUID = UUID.fromString("19B10000-E8F2-537E-4F6C-D104768A1214")
    private val uuidCaracteristica: UUID = UUID.fromString("19B10001-E8F2-537E-4F6C-D104768A1214")

    // Adaptador Bluetooth y conexión GATT
    private var adaptadorBluetooth: BluetoothAdapter? = null
    private var gattBluetooth: BluetoothGatt? = null

    // Preferencias compartidas para almacenar el último dispositivo conectado
    private lateinit var preferenciasCompartidas: SharedPreferences
    private var ultimaDireccionDispositivo: String? = null

    // Control de reconexión
    private var estaReconectando = false
    private val retrasoReconexion = 5000L // 5 segundos

    // Manejador para timeout y reconexión en el hilo principal
    private val manejador = Handler(Looper.getMainLooper())
    private var tiempoEscaneo = 30000L // 30 segundos de timeout para el escaneo

    // Lista para almacenar los dispositivos encontrados durante el escaneo
    private val listaDispositivos: MutableList<BluetoothDevice> = mutableListOf()

    // Referencias a la interfaz de usuario
    private lateinit var tvEstado: TextView
    private lateinit var btnAdelante: Button
    private lateinit var btnIzquierda: Button
    private lateinit var btnDerecha: Button
    private lateinit var btnAtras: Button
    private lateinit var btnSeguidor: Button
    private lateinit var btnEvadir: Button
    private lateinit var btnManual: Button
    private lateinit var btnParar: ImageView
    private lateinit var ivConnectionStatus: ImageView


    // Configuración del escaneo BLE
    private val configuracionEscaneo = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .build()

    // Filtro de escaneo: ajusta el nombre del dispositivo para que coincida con tu Arduino R4
    private val filtrosEscaneo = listOf(
        ScanFilter.Builder()
            .setDeviceName("Arduino R4")
            .build()
    )

    /**
     * Callback para el escaneo BLE.
     * En este caso, se van acumulando los dispositivos encontrados en una lista.
     */
    private val callbackEscaneoBLE = object : ScanCallback() {
        override fun onScanResult(tipoCallback: Int, resultado: ScanResult?) {
            resultado?.device?.let { dispositivo ->
                // Agrega el dispositivo a la lista si aún no existe
                if (!listaDispositivos.contains(dispositivo)) {
                    listaDispositivos.add(dispositivo)
                }
            }
        }

        override fun onScanFailed(codigoError: Int) {
            Log.e(ETIQUETA, "El escaneo BLE falló con error: $codigoError")
            manejador.postDelayed({ iniciarEscaneoBLE() }, retrasoReconexion)
        }
    }

    /**
     * Callback de la conexión GATT.
     * Tras la conexión y el descubrimiento de servicios, se verifica que el servicio esperado esté presente.
     */
    private val callbackGATT = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, estado: Int, nuevoEstado: Int) {
            when (nuevoEstado) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(ETIQUETA, "Conectado al servidor GATT")
                    gattBluetooth = gatt
                    ultimaDireccionDispositivo = gatt?.device?.address
                    guardarUltimoDispositivo(ultimaDireccionDispositivo)
                    estaReconectando = false

                    runOnUiThread {
                        tvEstado.setText(R.string.status_connected)
                        // Aún no habilitamos los botones; se hará tras verificar el servicio.
                        ivConnectionStatus.setImageResource(R.drawable.prendido)
                    }

                    // Comprueba permisos antes de descubrir servicios
                    if (ActivityCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        try {
                            gatt?.discoverServices()
                        } catch (e: SecurityException) {
                            Log.e(ETIQUETA, "Error de seguridad al descubrir servicios", e)
                        }
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(ETIQUETA, "Desconectado del servidor GATT")
                    runOnUiThread {
                        tvEstado.setText(R.string.status_disconnected)
                        ivConnectionStatus.setImageResource(R.drawable.apagado)
                        actualizarEstadoBotones(false)
                    }

                    // Cierra la conexión GATT
                    gattBluetooth?.close()
                    gattBluetooth = null

                    // Si no se está reconectando, intenta reconectar después de un retraso
                    if (!estaReconectando) {
                        estaReconectando = true
                        manejador.postDelayed({ reconectarUltimoDispositivo() }, retrasoReconexion)
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, estado: Int) {
            if (estado == BluetoothGatt.GATT_SUCCESS) {
                Log.d(ETIQUETA, "Servicios descubiertos correctamente")
                // Comprueba si se tiene el permiso BLUETOOTH_CONNECT antes de acceder al servicio
                if (ActivityCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    Log.e(ETIQUETA, "Permiso BLUETOOTH_CONNECT no otorgado")
                    return
                }

                // Verifica que el servicio esperado esté presente
                val servicio = gatt?.getService(uuidServicio)
                if (servicio != null) {
                    // Servicio encontrado: configura notificaciones y habilita botones
                    configurarNotificacionesCaracteristica(gatt)
                    runOnUiThread { actualizarEstadoBotones(true) }
                } else {
                    // Servicio no encontrado: el dispositivo no es válido
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Dispositivo inválido", Toast.LENGTH_SHORT).show()
                        actualizarEstadoBotones(false)
                    }
                    // Cierra la conexión
                    gattBluetooth?.close()
                    gattBluetooth = null
                }
            } else {
                Log.w(ETIQUETA, "El descubrimiento de servicios falló con estado: $estado")
            }
        }


        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            caracteristica: BluetoothGattCharacteristic?,
            estado: Int
        ) {
            val exito = (estado == BluetoothGatt.GATT_SUCCESS)
            Log.d(ETIQUETA, "Escritura en característica ${if (exito) "exitosa" else "fallida"}")
            if (!exito && caracteristica != null) {
                // Reintentar la escritura si es necesario
                reintentarEscrituraCaracteristica(caracteristica)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Carga el layout principal (activity_main.xml)
        setContentView(R.layout.activity_main)

        // Inicializa las preferencias compartidas y obtiene el último dispositivo conectado (si existe)
        preferenciasCompartidas = getSharedPreferences("BLEPrefs", Context.MODE_PRIVATE)
        ultimaDireccionDispositivo = preferenciasCompartidas.getString("LAST_DEVICE", null)

        inicializarVistas()
        configurarAdaptadorBluetooth()
        verificarYSolicitarPermisos()
    }

    /**
     * Vincula las vistas definidas en el layout XML y configura los listeners de los botones.
     */
    private fun inicializarVistas() {
        tvEstado = findViewById(R.id.tvSubtitle)
        ivConnectionStatus = findViewById(R.id.ivConnectionStatus)
        btnAdelante = findViewById(R.id.btnAdelante)
        btnIzquierda = findViewById(R.id.btnIzquierda)
        btnDerecha = findViewById(R.id.btnDerecha)
        btnAtras = findViewById(R.id.btnAtras)
        btnSeguidor = findViewById(R.id.btnSeguidor)
        btnEvadir = findViewById(R.id.btnEvadir)
        btnManual = findViewById(R.id.btnManual)
        btnParar = findViewById(R.id.btnParar)

        configurarListenersBotones()
        actualizarEstadoBotones(false)
    }

    /**
     * Configura los listeners de los botones para enviar comandos al dispositivo BLE.
     */
    private fun configurarListenersBotones() {
        btnAdelante.setOnClickListener { enviarComando("Adelante") }
        btnIzquierda.setOnClickListener { enviarComando("Izquierda") }
        btnDerecha.setOnClickListener { enviarComando("Derecha") }
        btnAtras.setOnClickListener { enviarComando("Atrás") }
        btnParar.setOnClickListener { enviarComando("Parar") }

        btnSeguidor.setOnClickListener {
            enviarComando("Seguidor")
            // Deshabilitar botones direccionales
            btnAdelante.isEnabled = false
            btnIzquierda.isEnabled = false
            btnDerecha.isEnabled = false
            btnAtras.isEnabled = false
            //btnParar.isEnabled = false
            // ImageView
            btnParar.isClickable = false
            btnParar.isFocusable = false
        }

        btnEvadir.setOnClickListener {
            enviarComando("Evadir")
            // Deshabilitar botones direccionales
            btnAdelante.isEnabled = false
            btnIzquierda.isEnabled = false
            btnDerecha.isEnabled = false
            btnAtras.isEnabled = false
            //btnParar.isEnabled = false
            // ImageView
            btnParar.isClickable = false
            btnParar.isFocusable = false
        }

        btnManual.setOnClickListener {
            enviarComando("Manual")
            // Habilitar nuevamente los botones direccionales
            btnAdelante.isEnabled = true
            btnIzquierda.isEnabled = true
            btnDerecha.isEnabled = true
            btnAtras.isEnabled = true
            //btnParar.isEnabled = true
            // ImageView
            btnParar.isClickable = false
            btnParar.isFocusable = false
        }
    }


    /**
     * Configura el adaptador Bluetooth y verifica que el dispositivo soporte BLE.
     */
    private fun configurarAdaptadorBluetooth() {
        val gestorBluetooth = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        adaptadorBluetooth = gestorBluetooth.adapter

        if (adaptadorBluetooth == null || !packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Log.e(ETIQUETA, "El dispositivo no soporta BLE")
            finish()
        }
    }

    /**
     * Verifica y solicita los permisos necesarios para el uso de BLE.
     */
    private fun verificarYSolicitarPermisos() {
        val permisos = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION // Para compatibilidad hacia atrás
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permisos.add(Manifest.permission.BLUETOOTH_SCAN)
            permisos.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        // Si algún permiso no está concedido, se solicita
        if (permisos.any {
                ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
        ) {
            ActivityCompat.requestPermissions(this, permisos.toTypedArray(), 1)
        } else {
            iniciarConexion()
        }
    }

    /**
     * Inicia la conexión: si hay un dispositivo conocido intenta reconectar, si no, inicia el escaneo.
     */
    private fun iniciarConexion() {
        ultimaDireccionDispositivo?.let {
            // Intenta reconectar al último dispositivo conocido
            reconectarUltimoDispositivo()
        } ?: run {
            // Si no hay dispositivo guardado, inicia el escaneo BLE
            iniciarEscaneoBLE()
        }
    }

    /**
     * Inicia el escaneo BLE comprobando previamente que se tenga el permiso BLUETOOTH_SCAN.
     * Los dispositivos encontrados se almacenan en listaDispositivos y, tras el timeout, se muestra el diálogo de selección.
     */
    private fun iniciarEscaneoBLE() {
        // Limpia la lista de dispositivos encontrados
        listaDispositivos.clear()

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        adaptadorBluetooth?.bluetoothLeScanner?.startScan(filtrosEscaneo, configuracionEscaneo, callbackEscaneoBLE)
        Log.d(ETIQUETA, "Escaneo BLE iniciado...")

        // Programa el fin del escaneo después de 'tiempoEscaneo' milisegundos
        manejador.postDelayed({ detenerEscaneoBLE(); mostrarDialogoSeleccionDispositivos() }, tiempoEscaneo)
    }

    /**
     * Detiene el escaneo BLE comprobando el permiso BLUETOOTH_SCAN.
     */
    private fun detenerEscaneoBLE() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        adaptadorBluetooth?.bluetoothLeScanner?.stopScan(callbackEscaneoBLE)
        Log.d(ETIQUETA, "Escaneo BLE detenido")
    }

    /**
     * Muestra un diálogo para que el usuario seleccione el dispositivo de la lista encontrada.
     */
    private fun mostrarDialogoSeleccionDispositivos() {
        if (listaDispositivos.isEmpty()) {
            Toast.makeText(this, "No se encontraron dispositivos.", Toast.LENGTH_SHORT).show()
            return
        }

        // Crea una lista de nombres y direcciones para mostrar en el diálogo
        val nombresDispositivos = listaDispositivos.map { dispositivo ->
            val nombre = if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED
            ) dispositivo.name ?: "Desconocido" else "Desconocido"
            "$nombre (${dispositivo.address})"
        }.toTypedArray()

        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("Selecciona un dispositivo")
                .setItems(nombresDispositivos) { _: DialogInterface, indice: Int ->
                    val dispositivoSeleccionado = listaDispositivos[indice]
                    conectarDispositivo(dispositivoSeleccionado)
                }
                .setNegativeButton("Cancelar") { dialog, _ -> dialog.dismiss() }
                .show()
        }
    }

    /**
     * Comprueba si el dispositivo coincide con el nombre esperado o es el último dispositivo conectado.
     */
    private fun esDispositivoCoincidente(dispositivo: BluetoothDevice): Boolean {
        val nombreDispositivo = if (
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            == PackageManager.PERMISSION_GRANTED
        ) {
            dispositivo.name
        } else {
            null
        }

        return nombreDispositivo?.contains("Arduino") == true ||
                dispositivo.address == ultimaDireccionDispositivo
    }

    /**
     * Conecta al dispositivo BLE comprobando el permiso BLUETOOTH_CONNECT.
     */
    private fun conectarDispositivo(dispositivo: BluetoothDevice) {
        Log.d(ETIQUETA, "Conectando a: ${dispositivo.address}")
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        // Se utiliza connectGatt para iniciar la conexión en modo BLE
        dispositivo.connectGatt(this, false, callbackGATT, BluetoothDevice.TRANSPORT_LE)
    }

    /**
     * Intenta reconectar al último dispositivo conocido.
     */
    private fun reconectarUltimoDispositivo() {
        ultimaDireccionDispositivo?.let { direccion ->
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            adaptadorBluetooth?.getRemoteDevice(direccion)?.let { dispositivo ->
                conectarDispositivo(dispositivo)
            }
        } ?: iniciarEscaneoBLE()
    }

    /**
     * Configura las notificaciones para la característica, comprobando el permiso BLUETOOTH_CONNECT.
     */
    private fun configurarNotificacionesCaracteristica(gatt: BluetoothGatt?) {
        val servicio = gatt?.getService(uuidServicio)
        val caracteristica = servicio?.getCharacteristic(uuidCaracteristica)

        if (caracteristica != null &&
            (caracteristica.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
        ) {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                gatt.setCharacteristicNotification(caracteristica, true)
            }
        }
    }

    /**
     * Envía un comando al dispositivo BLE.
     * Se comprueba que la característica esté disponible y sea escribible.
     */
    private fun enviarComando(comando: String) {
        if (!esCaracteristicaEscribible()) {
            Log.e(ETIQUETA, "La característica no es escribible")
            return
        }
        gattBluetooth?.let { gatt ->
            val servicio = gatt.getService(uuidServicio)
            val caracteristica = servicio?.getCharacteristic(uuidCaracteristica)

            if (caracteristica == null) {
                Log.e(ETIQUETA, "Característica no encontrada")
                return
            }

            // Establece el valor del comando (convertido a arreglo de bytes)
            caracteristica.value = comando.toByteArray()

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }

            try {
                // Para Android T (API 33) y superiores se usa la nueva API
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val exito = gatt.writeCharacteristic(
                        caracteristica,
                        comando.toByteArray(),
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    )
                    Log.d(ETIQUETA, "writeCharacteristic (nueva API) retornó: $exito")
                } else {
                    // Para versiones anteriores se utiliza la API legacy
                    caracteristica.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    val exito = gatt.writeCharacteristic(caracteristica)
                    Log.d(ETIQUETA, "writeCharacteristic (API legacy) retornó: $exito")
                }
                Log.d(ETIQUETA, "Comando enviado: $comando")
            } catch (e: Exception) {
                Log.e(ETIQUETA, "Error al enviar el comando", e)
            }
        }
    }

    /**
     * Reintenta la escritura en la característica con un retraso de 1 segundo.
     */
    private fun reintentarEscrituraCaracteristica(caracteristica: BluetoothGattCharacteristic) {
        manejador.postDelayed({
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED
            ) {
                gattBluetooth?.writeCharacteristic(caracteristica)
            }
        }, 1000)
    }

    /**
     * Verifica si la característica es escribible.
     */
    private fun esCaracteristicaEscribible(): Boolean {
        val servicio = gattBluetooth?.getService(uuidServicio)
        val caracteristica = servicio?.getCharacteristic(uuidCaracteristica)

        return caracteristica != null &&
                (caracteristica.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
    }

    /**
     * Guarda la dirección del último dispositivo conectado en las preferencias compartidas.
     */
    private fun guardarUltimoDispositivo(direccion: String?) {
        preferenciasCompartidas.edit().putString("LAST_DEVICE", direccion).apply()
    }

    /**
     * Actualiza el estado de los botones en la interfaz (habilitados o deshabilitados).
     */
    private fun actualizarEstadoBotones(habilitado: Boolean) {
        btnAdelante.isEnabled = habilitado
        btnIzquierda.isEnabled = habilitado
        btnDerecha.isEnabled = habilitado
        btnAtras.isEnabled = habilitado
        btnSeguidor.isEnabled = habilitado
        btnEvadir.isEnabled = habilitado
        btnManual.isEnabled = habilitado
       // btnParar.isEnabled = habilitado
        // ImageView
        btnParar.isClickable = habilitado
        btnParar.isFocusable = habilitado
    }

    override fun onDestroy() {
        super.onDestroy()
        manejador.removeCallbacksAndMessages(null)

        // Cierra la conexión GATT comprobando que se tenga el permiso BLUETOOTH_CONNECT
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                gattBluetooth?.close()
            } catch (e: SecurityException) {
                Log.e(ETIQUETA, "Excepción de seguridad al cerrar GATT", e)
            }
        } else {
            Log.w(ETIQUETA, "No se puede cerrar GATT: permiso BLUETOOTH_CONNECT no otorgado")
        }
        gattBluetooth = null
    }
}
