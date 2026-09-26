package com.uberspot.pro

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import java.util.Locale
import java.util.regex.Pattern

class RideAccessibilityService : AccessibilityService() {

    companion object {
        var instance: RideAccessibilityService? = null
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "uberspot_pro_channel"
    }

    private lateinit var prefs: SharedPreferences
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var isOverlayAttached = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var dismissRunnable: Runnable? = null

    // Tracks currently displayed offer to avoid re-rendering identical trip
    private var currentOfferKey = ""
    private var lastProcessTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        prefs = getSharedPreferences("UberSpotPrefs", Context.MODE_PRIVATE)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        setupOngoingNotification()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        removeOverlayView()
        removeNotification()
    }

    private fun setupOngoingNotification() {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "UberSpot Pro Asistente",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Mantiene activo el asistente de viajes durante tu turno"
                }
                notificationManager.createNotificationChannel(channel)
            }

            val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }.apply {
                setContentTitle("🟢 UberSpot Pro Activo")
                setContentText("Monitoreando ofertas de Uber Driver en tiempo real")
                setSmallIcon(android.R.drawable.ic_menu_compass)
                setOngoing(true)
            }.build()

            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun removeNotification() {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {}
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // 1. Instant check: Is assistant enabled?
        if (!prefs.getBoolean("service_enabled", true)) return

        val now = System.currentTimeMillis()
        if (now - lastProcessTime < 30) return // Ultra-responsive 30ms throttle

        val pkgName = (event.packageName ?: "").toString().lowercase()
        if (!pkgName.contains("uber") && !pkgName.contains("systemui")) return

        // 2. Fast Path: First check event.source (immediately available when card pops up)
        var parsed = false
        val sourceNode = event.source
        if (sourceNode != null) {
            val sourceCollector = StringBuilder()
            collectTextsRecursively(sourceNode, sourceCollector)
            val sourceText = sourceCollector.toString()
            if (hasUberOfferMarkers(sourceText)) {
                parsed = parseUberOfferAndEvaluate(sourceText)
            }
        }

        // 3. Fallback: If event.source didn't have the full offer, check rootInActiveWindow
        if (!parsed) {
            val rootNode = rootInActiveWindow
            if (rootNode != null) {
                val rootCollector = StringBuilder()
                collectTextsRecursively(rootNode, rootCollector)
                val rootText = rootCollector.toString()
                if (hasUberOfferMarkers(rootText)) {
                    parsed = parseUberOfferAndEvaluate(rootText)
                }
            }
        }

        if (parsed) {
            lastProcessTime = now
        }
    }

    private fun hasUberOfferMarkers(text: String): Boolean {
        if (text.length < 15) return false
        val lower = text.lowercase()
        return (lower.contains("economy") ||
                lower.contains("uberx") ||
                lower.contains("comfort") ||
                lower.contains("flash") ||
                lower.contains("me interesa") ||
                lower.contains("viaje:") ||
                lower.contains("(estimado)") ||
                lower.contains("contrato de renta")) &&
               (lower.contains("cop") || lower.contains("$") || lower.contains("km"))
    }

    private fun collectTextsRecursively(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (node == null) return
        val text = node.text?.toString()
        if (!text.isNullOrBlank()) {
            sb.append(text).append("\n")
        }
        val desc = node.contentDescription?.toString()
        if (!desc.isNullOrBlank() && desc != text) {
            sb.append(desc).append("\n")
        }
        val count = node.childCount
        for (i in 0 until count) {
            collectTextsRecursively(node.getChild(i), sb)
        }
    }

    private fun parseUberOfferAndEvaluate(rawText: String): Boolean {
        // Normalize all Unicode non-breaking spaces (\u00A0, \u202F, etc.)
        val cleanText = rawText.replace(Regex("[\\u00A0\\u202F\\u2000-\\u200B]"), " ")

        // 1. FARE EXTRACTION
        val lines = cleanText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        var fare = 0

        // Strategy A: Trip fare is the line right before '(estimado)' or '/km'
        for (idx in 0 until lines.size - 1) {
            val curr = lines[idx]
            val nxt = lines[idx + 1].lowercase()
            if (nxt.contains("/km") || nxt.contains("estimado")) {
                val m = Pattern.compile("(?:COP|\\$)?\\s*([0-9]{1,3}(?:[.,][0-9]{3})+|[0-9]{4,6})", Pattern.CASE_INSENSITIVE).matcher(curr)
                if (m.find()) {
                    val numStr = m.group(1)?.replace(".", "")?.replace(",", "") ?: ""
                    val cand = numStr.toIntOrNull() ?: 0
                    if (cand in 4000..350000) {
                        fare = cand
                        break
                    }
                }
            }
        }

        // Strategy B: Line immediately following category name
        if (fare == 0) {
            for (idx in 0 until lines.size - 1) {
                val curr = lines[idx].lowercase()
                if (curr.contains("economy") || curr.contains("uberx") || curr.contains("comfort") ||
                    curr.contains("flash") || curr.contains("moto")) {
                    val nxt = lines[idx + 1]
                    val m = Pattern.compile("(?:COP|\\$)?\\s*([0-9]{1,3}(?:[.,][0-9]{3})+|[0-9]{4,6})", Pattern.CASE_INSENSITIVE).matcher(nxt)
                    if (m.find()) {
                        val numStr = m.group(1)?.replace(".", "")?.replace(",", "") ?: ""
                        val cand = numStr.toIntOrNull() ?: 0
                        if (cand in 4000..350000) {
                            fare = cand
                            break
                        }
                    }
                }
            }
        }

        // Strategy C: Scan from bottom of lines
        if (fare == 0) {
            for (i in lines.size - 1 downTo 0) {
                val line = lines[i]
                val l = line.lowercase()
                if (l.contains("/km") || l.contains("+cop") || l.contains("+$") ||
                    l.contains("saldo") || l.contains("hoy") || l.contains("ganancia")) continue

                val m = Pattern.compile("(?:COP|\\$)\\s*([0-9]{1,3}(?:[.,][0-9]{3})+|[0-9]{4,6})", Pattern.CASE_INSENSITIVE).matcher(line)
                if (m.find()) {
                    val numStr = m.group(1)?.replace(".", "")?.replace(",", "") ?: ""
                    val cand = numStr.toIntOrNull() ?: 0
                    if (cand in 4000..350000) {
                        fare = cand
                        break
                    }
                }
            }
        }

        if (fare < 4000) return false

        // 2. DISTANCES & TIMES
        var totalKm = 0.0
        var totalMin = 0

        // Combined: "X min (Y km)" or "X min (Y m)"
        val legMatcher = Pattern.compile(
            "([0-9]+)\\s*min(?:uto)?s?\\s*\\(\\s*([0-9]+(?:[.,][0-9]+)?)\\s*(km|m)\\s*\\)",
            Pattern.CASE_INSENSITIVE
        ).matcher(cleanText)

        var legsFound = 0
        while (legMatcher.find()) {
            legsFound++
            val m = legMatcher.group(1)?.toIntOrNull() ?: 0
            val dStr = legMatcher.group(2)?.replace(",", ".") ?: "0"
            val d = dStr.toDoubleOrNull() ?: 0.0
            val unit = legMatcher.group(3) ?: "km"

            totalMin += m
            if (unit.equals("m", ignoreCase = true)) {
                totalKm += d / 1000.0
            } else {
                totalKm += d
            }
        }

        // Fallback to separate tokens
        if (legsFound == 0) {
            val kmMatcher = Pattern.compile("([0-9]+(?:[.,][0-9]+)?)\\s*(?:km|kms)\\b", Pattern.CASE_INSENSITIVE).matcher(cleanText)
            while (kmMatcher.find()) {
                val d = kmMatcher.group(1)?.replace(",", ".")?.toDoubleOrNull() ?: 0.0
                if (d in 0.4..70.0) totalKm += d
            }

            val mMatcher = Pattern.compile("([0-9]{2,4})\\s*(?:m|metros)\\b", Pattern.CASE_INSENSITIVE).matcher(cleanText)
            while (mMatcher.find()) {
                val mVal = mMatcher.group(1)?.toDoubleOrNull() ?: 0.0
                if (mVal in 50.0..2500.0) totalKm += mVal / 1000.0
            }

            val minMatcher = Pattern.compile("([0-9]{1,3})\\s*(?:min|mins|minuto|minutos)\\b", Pattern.CASE_INSENSITIVE).matcher(cleanText)
            while (minMatcher.find()) {
                val t = minMatcher.group(1)?.toIntOrNull() ?: 0
                if (t in 1..180) totalMin += t
            }
        }

        if (totalKm <= 0.0) return false

        // Check if this exact offer is already shown
        val offerKey = "$fare-$totalKm-$totalMin"
        if (offerKey == currentOfferKey) return true
        currentOfferKey = offerKey

        // 3. FINANCIAL CALCULATION - OPCIÓN B (MAYOR ENTRE KM Y TIEMPO)
        val minRateKm = prefs.getInt("min_rate_km", 1800)
        val minRateMin = prefs.getInt("min_rate_min", 400)
        val fuelType = prefs.getString("fuel_type", "gnv") ?: "gnv"

        val fuelPerKm = if (fuelType == "gnv") 200 else 420
        val fuelCost = (totalKm * fuelPerKm).toInt()
        val commRate = if (cleanText.lowercase().contains("0% de tarifa")) 0.0 else 0.20
        val commAmount = (fare * commRate).toInt()
        val netProfit = (fare - commAmount - fuelCost).coerceAtLeast(0)

        val perKm = (fare / totalKm).toInt()
        val netPerHour = if (totalMin > 0) ((netProfit.toDouble() / totalMin) * 60).toInt() else 0

        val targetKmFare = (totalKm * minRateKm).toInt()
        val targetMinFare = (totalMin * minRateMin).toInt()
        val rawFairPrice = maxOf(targetKmFare, targetMinFare)
        val fairPrice = (Math.round(rawFairPrice / 100.0) * 100).toInt()

        val verdict = when {
            fare >= fairPrice && perKm >= minRateKm -> "ACCEPT"
            perKm >= (minRateKm * 0.85) -> "REGULAR"
            else -> "REJECT"
        }

        // 4. DISPLAY FLOATING OVERLAY IMMEDIATELY (ZERO DELAY)
        mainHandler.post {
            showOverlayDirect(
                appName = "Uber",
                fare = fare,
                perKm = perKm,
                netPerHour = netPerHour,
                km = totalKm,
                min = totalMin,
                verdict = verdict,
                netProfit = netProfit,
                fairPrice = fairPrice
            )
        }

        return true
    }

    fun showOverlayDirect(
        appName: String,
        fare: Int,
        perKm: Int,
        netPerHour: Int,
        km: Double,
        min: Int,
        verdict: String,
        netProfit: Int,
        fairPrice: Int
    ) {
        try {
            if (windowManager == null) {
                windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            }

            // CLEAN WINDOW LIFECYCLE: Remove previous view if still attached so we always add fresh
            if (isOverlayAttached && overlayView != null) {
                try {
                    windowManager?.removeView(overlayView)
                } catch (e: Exception) {}
                isOverlayAttached = false
            }

            val inflater = LayoutInflater.from(this)
            overlayView = inflater.inflate(R.layout.overlay_bubble, null)
            overlayView?.setOnClickListener {
                hideOverlay()
            }

            val pillContainer = overlayView?.findViewById<View>(R.id.pillContainer)
            val tvAppBadge = overlayView?.findViewById<TextView>(R.id.tvAppBadge)
            val tvTitle = overlayView?.findViewById<TextView>(R.id.tvVerdictTitle)
            val tvFair = overlayView?.findViewById<TextView>(R.id.tvFairPrice)
            val tvSub = overlayView?.findViewById<TextView>(R.id.tvVerdictSub)

            tvAppBadge?.text = "UBER"
            val badgeBg = GradientDrawable().apply {
                setColor(Color.parseColor("#0f172a"))
                setStroke(2, Color.parseColor("#cbd5e1"))
                cornerRadius = 14f
            }
            tvAppBadge?.background = badgeBg

            val kmFormatted = String.format(Locale.US, "%.1f", km)
            val kPerHour = netPerHour / 1000
            val diffPrice = fairPrice - fare

            val strokeColor: Int
            when (verdict) {
                "ACCEPT" -> {
                    strokeColor = Color.parseColor("#10b981")
                    tvTitle?.text = "🟢 ACEPTAR • \$$perKm / km"
                    tvTitle?.setTextColor(Color.parseColor("#34d399"))
                    tvFair?.text = "💡 Tarifa Justa: \$$fairPrice (¡Paga excelente!)"
                    tvFair?.setTextColor(Color.parseColor("#a7f3d0"))
                }
                "REJECT" -> {
                    strokeColor = Color.parseColor("#ef4444")
                    tvTitle?.text = "🔴 RECHAZAR • \$$perKm / km"
                    tvTitle?.setTextColor(Color.parseColor("#f87171"))
                    val diffText = if (diffPrice > 0) " (Faltan \$$diffPrice)" else ""
                    tvFair?.text = "💡 Debería pagar: \$$fairPrice$diffText"
                    tvFair?.setTextColor(Color.parseColor("#fef08a"))
                }
                else -> {
                    strokeColor = Color.parseColor("#f59e0b")
                    tvTitle?.text = "🟡 REGULAR • \$$perKm / km"
                    tvTitle?.setTextColor(Color.parseColor("#fbbf24"))
                    val diffText = if (diffPrice > 0) " (Faltan \$$diffPrice)" else ""
                    tvFair?.text = "💡 Debería pagar: \$$fairPrice$diffText"
                    tvFair?.setTextColor(Color.parseColor("#ffffff"))
                }
            }

            val containerDrawable = GradientDrawable().apply {
                setColor(Color.parseColor("#0f172a"))
                setStroke(4, strokeColor)
                cornerRadius = 32f
            }
            pillContainer?.background = containerDrawable

            tvSub?.text = "$appName: \$$fare (\$$netProfit neto) | ~\$${kPerHour}k/h ($kmFormatted km • $min min)"

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = 80
            }

            windowManager?.addView(overlayView, params)
            isOverlayAttached = true

            dismissRunnable?.let { mainHandler.removeCallbacks(it) }
            dismissRunnable = Runnable {
                hideOverlay()
            }
            mainHandler.postDelayed(dismissRunnable!!, 15000)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun hideOverlay() {
        try {
            dismissRunnable?.let { mainHandler.removeCallbacks(it) }
            if (isOverlayAttached && overlayView != null) {
                windowManager?.removeView(overlayView)
                isOverlayAttached = false
            }
            currentOfferKey = "" // Reset offer key so next offer appears immediately
        } catch (e: Exception) {
            isOverlayAttached = false
            currentOfferKey = ""
        }
    }

    private fun removeOverlayView() {
        try {
            dismissRunnable?.let { mainHandler.removeCallbacks(it) }
            if (isOverlayAttached && overlayView != null) {
                windowManager?.removeView(overlayView)
                isOverlayAttached = false
            }
            currentOfferKey = ""
        } catch (e: Exception) {}
    }

    override fun onInterrupt() {
        removeOverlayView()
    }
}
