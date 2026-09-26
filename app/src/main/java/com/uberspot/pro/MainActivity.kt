package com.uberspot.pro

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
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

        val etKm = findViewById<EditText>(R.id.etMinKm)
        val etMin = findViewById<EditText>(R.id.etMinTime)
        val rgFuel = findViewById<RadioGroup>(R.id.rgFuel)
        val btnSave = findViewById<Button>(R.id.btnSaveSettings)

        val btnPermOverlay = findViewById<Button>(R.id.btnPermOverlay)
        val btnPermAccess = findViewById<Button>(R.id.btnPermAccess)
        val btnTestUber = findViewById<Button>(R.id.btnTestUber)

        // 1. MASTER SWITCH
        val isEnabled = prefs.getBoolean("service_enabled", true)
        swActive.isChecked = isEnabled
        updateMasterSwitchState(swActive, tvSwitchSub, isEnabled)

        swActive.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("service_enabled", isChecked).apply()
            updateMasterSwitchState(swActive, tvSwitchSub, isChecked)
            val msg = if (isChecked) "🟢 Asistente ACTIVADO (En Turno)" else "⚪ Asistente PAUSADO (En Descanso)"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // 2. FINANCIAL INPUTS (OPTION B)
        etKm.setText(prefs.getInt("min_rate_km", 1800).toString())
        etMin.setText(prefs.getInt("min_rate_min", 400).toString())

        if (prefs.getString("fuel_type", "gnv") == "gasolina") {
            rgFuel.check(R.id.rbGas)
        } else {
            rgFuel.check(R.id.rbGnv)
        }

        btnSave.setOnClickListener {
            val kmVal = etKm.text.toString().toIntOrNull() ?: 1800
            val minVal = etMin.text.toString().toIntOrNull() ?: 400
            val fuel = if (rgFuel.checkedRadioButtonId == R.id.rbGas) "gasolina" else "gnv"

            prefs.edit()
                .putInt("min_rate_km", kmVal)
                .putInt("min_rate_min", minVal)
                .putString("fuel_type", fuel)
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

        // 4. DIRECT OVERLAY TEST
        btnTestUber.setOnClickListener {
            val srv = RideAccessibilityService.instance
            if (srv != null) {
                // Test trip from screenshot: COP 6.525, 2.1 km, 8 min
                srv.showOverlayDirect(
                    appName = "Uber",
                    fare = 6525,
                    perKm = 3107,
                    netPerHour = 36000,
                    km = 2.1,
                    min = 8,
                    verdict = "ACCEPT",
                    netProfit = 4800,
                    fairPrice = 3800
                )
                Toast.makeText(this, "Burbuja de prueba lanzada en pantalla", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "⚠️ Por favor activa primero el 'Servicio de Accesibilidad' abajo", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            }
        }
    }

    private fun updateMasterSwitchState(sw: Switch, tvSub: TextView, isChecked: Boolean) {
        if (isChecked) {
            sw.text = "🟢 Asistente ACTIVO (En Turno)"
            sw.setTextColor(Color.parseColor("#10b981"))
            tvSub.text = "Monitoreando Uber Driver en vivo (Respuesta en 20ms)"
        } else {
            sw.text = "⚪ Asistente PAUSADO (En Descanso)"
            sw.setTextColor(Color.parseColor("#94a3b8"))
            tvSub.text = "Asistente en reposo. Cero consumo de batería."
        }
    }
}
