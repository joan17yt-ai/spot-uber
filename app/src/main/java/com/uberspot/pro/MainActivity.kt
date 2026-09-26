package com.uberspot.pro

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("UberSpotPrefs", Context.MODE_PRIVATE)

        val swActive = findViewById<Switch>(R.id.swServiceActive)
        val tvSwitchSub = findViewById<TextView>(R.id.tvSwitchSub)

        val etMaxPickup = findViewById<EditText>(R.id.etMaxPickup)
        val etKm = findViewById<EditText>(R.id.etMinKm)
        val etMin = findViewById<EditText>(R.id.etMinTime)
        val btnSave = findViewById<Button>(R.id.btnSaveSettings)

        val btnPermOverlay = findViewById<Button>(R.id.btnPermOverlay)
        val btnPermAccess = findViewById<Button>(R.id.btnPermAccess)
        val btnBattery = findViewById<Button>(R.id.btnBatteryOptimize)
        val btnTestUber = findViewById<Button>(R.id.btnTestUber)

        // Request POST_NOTIFICATIONS on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        // 1. MASTER SWITCH
        val isEnabled = prefs.getBoolean("service_enabled", true)
        swActive.isChecked = isEnabled
        updateMasterSwitchState(swActive, tvSwitchSub, isEnabled)

        swActive.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("service_enabled", isChecked).apply()
            updateMasterSwitchState(swActive, tvSwitchSub, isChecked)
            
            RideAccessibilityService.instance?.updateStatusNotification(isChecked)
            
            val msg = if (isChecked) "🟢 Asistente ACTIVADO (En Turno)" else "⚪ Asistente PAUSADO (En Descanso)"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // 2. FINANCIAL INPUTS & PICKUP FILTER
        etMaxPickup.setText(prefs.getFloat("max_pickup_km", 2.5f).toString())
        etKm.setText(prefs.getInt("min_rate_km", 1800).toString())
        etMin.setText(prefs.getInt("min_rate_min", 400).toString())

        btnSave.setOnClickListener {
            val pickupVal = etMaxPickup.text.toString().toFloatOrNull() ?: 2.5f
            val kmVal = etKm.text.toString().toIntOrNull() ?: 1800
            val minVal = etMin.text.toString().toIntOrNull() ?: 400

            prefs.edit()
                .putFloat("max_pickup_km", pickupVal)
                .putInt("min_rate_km", kmVal)
                .putInt("min_rate_min", minVal)
                .apply()

            Toast.makeText(this, "✓ Parámetros guardados con éxito", Toast.LENGTH_SHORT).show()
        }

        // 3. PERMISSIONS
        btnPermOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                startActivity(intent)
            } else {
                Toast.makeText(this, "Permiso de superposición activo", Toast.LENGTH_SHORT).show()
            }
        }

        btnPermAccess.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "Activa 'UberSpot Pro' en Accesibilidad", Toast.LENGTH_LONG).show()
        }

        // 4. BATTERY UNRESTRICTED BUTTON (FOR XIAOMI / LONG SHIFTS)
        btnBattery.setOnClickListener {
            openBatterySettings()
        }

        // 5. DIRECT OVERLAY TEST
        btnTestUber.setOnClickListener {
            val srv = RideAccessibilityService.instance
            if (srv != null) {
                // Test trip from screenshot: COP 6.679, pickup: 2.5 km, trip: 2.8 km -> total: 5.3 km, 17 min
                srv.updateDockWithOffer(
                    totalKm = 5.3,
                    totalMin = 17,
                    pickupKm = 2.5,
                    perKm = 1260,
                    perMin = 392,
                    fairPrice = 9500,
                    diffPrice = -2821,
                    verdict = "REJECT_FAR",
                    isPickupTooFar = true
                )
                Toast.makeText(this, "Simulando oferta en Mini HUD (vuelve a 0 en 15s)...", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "⚠️ Por favor activa primero el 'Servicio de Accesibilidad' abajo", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            }
        }
    }

    @SuppressLint("BatteryLife")
    private fun openBatterySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    val appIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(appIntent)
                }
            } else {
                Toast.makeText(this, "✓ Batería ya configurada en 'Sin Restricciones'", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "No requerido en esta versión de Android", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateMasterSwitchState(sw: Switch, tvSub: TextView, isChecked: Boolean) {
        if (isChecked) {
            sw.text = "🟢 Asistente ACTIVO (En Turno)"
            sw.setTextColor(Color.parseColor("#10b981"))
            tvSub.text = "Mini HUD activo en pantalla (0ms de delay)"
        } else {
            sw.text = "⚪ Asistente PAUSADO (En Descanso)"
            sw.setTextColor(Color.parseColor("#94a3b8"))
            tvSub.text = "Asistente en reposo. Cero consumo de batería."
        }
    }
}
