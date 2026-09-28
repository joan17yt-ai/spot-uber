package com.uberspot.pro

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.MotionEvent
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
        const val CHANNEL_ID = "kaptor_channel"
    }

    private lateinit var prefs: SharedPreferences
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayLayoutParams: WindowManager.LayoutParams? = null
    private var isOverlayAttached = false
    private var isCollapsed = false

    // Drag and drop tracking
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var resetRunnable: Runnable? = null

    // Track active offer to avoid redundant calculations
    private var currentOfferKey = ""
    private var lastProcessTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        prefs = getSharedPreferences("KaptorPrefs", Context.MODE_PRIVATE)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        updateStatusNotification(prefs.getBoolean("service_enabled", true))

        // Attach always-fixed dock immediately in idle/zero state
        mainHandler.post {
            ensureDockAttached()
            resetDockToIdle()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        removeOverlayView()
        removeNotification()
    }

    fun updateStatusNotification(isEnabled: Boolean) {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Kaptor Monitoreo",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Copiloto táctico de Joan Lizarazo"
                }
                notificationManager.createNotificationChannel(channel)
            }

            val title = if (isEnabled) "⚡ Kaptor Activo" else "⚪ Kaptor Pausado"
            val text = if (isEnabled) "Monitoreando ofertas de viaje (Algoritmo de Joan Lizarazo)" else "Asistente en reposo. Cero consumo de batería."

            val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }.apply {
                setContentTitle(title)
                setContentText(text)
                setSmallIcon(android.R.drawable.ic_menu_compass)
                setOngoing(isEnabled)
            }.build()

            notificationManager.notify(NOTIFICATION_ID, notification)

            mainHandler.post {
                if (isEnabled) {
                    ensureDockAttached()
                    resetDockToIdle()
                } else {
                    removeOverlayView()
                }
            }
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

        if (!prefs.getBoolean("service_enabled", true)) return

        val now = System.currentTimeMillis()
        if (now - lastProcessTime < 30) return // 30ms fast path

        val pkgName = (event.packageName ?: "").toString().lowercase()
        if (!pkgName.contains("uber") && !pkgName.contains("systemui")) return

        // 1. Check event.source first
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

        // 2. Fallback to rootInActiveWindow
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
                lower.contains("moto") ||
                lower.contains("prioridad") ||
                lower.contains("priority") ||
                lower.contains("me interesa") ||
                lower.contains("aceptar") ||
                lower.contains("viaje:") ||
                lower.contains("(estimado)") ||
                lower.contains("recogida") ||
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
        val cleanText = rawText.replace(Regex("[\\u00A0\\u202F\\u2000-\\u200B]"), " ")

        // 1. FARE EXTRACTION
        val lines = cleanText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        var fare = 0

        // Strategy A: Trip fare right before '(estimado)' or '/km'
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

        // Strategy B: Line after category
        if (fare == 0) {
            for (idx in 0 until lines.size - 1) {
                val curr = lines[idx].lowercase()
                if (curr.contains("economy") || curr.contains("uberx") || curr.contains("comfort") ||
                    curr.contains("flash") || curr.contains("moto") || curr.contains("prioridad")) {
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

        // Strategy C: Bottom-up line scan
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

        // 2. DISTANCES & TIMES (SEPARATING PICKUP VS TRIP)
        var pickupKm = 0.0
        var totalKm = 0.0
        var totalMin = 0

        // Combined Pattern 1: "X min (Y km)" or "X min (Y m)"
        val legMatcher1 = Pattern.compile(
            "([0-9]+)\\s*min(?:uto)?s?\\s*\\(\\s*([0-9]+(?:[.,][0-9]+)?)\\s*(km|m)\\s*\\)",
            Pattern.CASE_INSENSITIVE
        ).matcher(cleanText)

        var legsFound = 0
        while (legMatcher1.find()) {
            legsFound++
            val m = legMatcher1.group(1)?.toIntOrNull() ?: 0
            val dStr = legMatcher1.group(2)?.replace(",", ".") ?: "0"
            val d = dStr.toDoubleOrNull() ?: 0.0
            val unit = legMatcher1.group(3) ?: "km"
            val legKm = if (unit.equals("m", ignoreCase = true)) d / 1000.0 else d

            if (legsFound == 1) {
                pickupKm = legKm
            }

            totalMin += m
            totalKm += legKm
        }

        // Fallback Pattern 2: "Y km (X min)"
        if (legsFound == 0) {
            val legMatcher2 = Pattern.compile(
                "([0-9]+(?:[.,][0-9]+)?)\\s*(km|m)\\s*\\(\\s*([0-9]+)\\s*min(?:uto)?s?\\s*\\)",
                Pattern.CASE_INSENSITIVE
            ).matcher(cleanText)
            while (legMatcher2.find()) {
                legsFound++
                val dStr = legMatcher2.group(1)?.replace(",", ".") ?: "0"
                val d = dStr.toDoubleOrNull() ?: 0.0
                val unit = legMatcher2.group(2) ?: "km"
                val m = legMatcher2.group(3)?.toIntOrNull() ?: 0
                val legKm = if (unit.equals("m", ignoreCase = true)) d / 1000.0 else d

                if (legsFound == 1) {
                    pickupKm = legKm
                }

                totalMin += m
                totalKm += legKm
            }
        }

        // Token fallback
        if (legsFound == 0) {
            val kmMatcher = Pattern.compile("([0-9]+(?:[.,][0-9]+)?)\\s*(?:km|kms)\\b", Pattern.CASE_INSENSITIVE).matcher(cleanText)
            var kCount = 0
            while (kmMatcher.find()) {
                kCount++
                val d = kmMatcher.group(1)?.replace(",", ".")?.toDoubleOrNull() ?: 0.0
                if (d in 0.4..70.0) {
                    if (kCount == 1) pickupKm = d
                    totalKm += d
                }
            }

            val mMatcher = Pattern.compile("([0-9]{2,4})\\s*(?:m|metros)\\b", Pattern.CASE_INSENSITIVE).matcher(cleanText)
            while (mMatcher.find()) {
                val mVal = mMatcher.group(1)?.toDoubleOrNull() ?: 0.0
                if (mVal in 50.0..2500.0) {
                    val mKm = mVal / 1000.0
                    if (pickupKm == 0.0) pickupKm = mKm
                    totalKm += mKm
                }
            }

            val minMatcher = Pattern.compile("([0-9]{1,3})\\s*(?:min|mins|minuto|minutos)\\b", Pattern.CASE_INSENSITIVE).matcher(cleanText)
            while (minMatcher.find()) {
                val t = minMatcher.group(1)?.toIntOrNull() ?: 0
                if (t in 1..180) totalMin += t
            }
        }

        if (totalKm <= 0.0) return false

        val offerKey = "$fare-$totalKm-$totalMin"
        if (offerKey == currentOfferKey) return true
        currentOfferKey = offerKey

        // 3. FINANCIAL CALCULATIONS - OPCION B & MAX PICKUP FILTER
        val minRateKm = prefs.getInt("min_rate_km", 1800)
        val minRateMin = prefs.getInt("min_rate_min", 400)
        val maxPickupKm = prefs.getFloat("max_pickup_km", 2.5f)

        val perKm = (fare / totalKm).toInt()
        val perMin = if (totalMin > 0) (fare / totalMin) else 0

        val targetKmFare = (totalKm * minRateKm).toInt()
        val targetMinFare = (totalMin * minRateMin).toInt()
        val rawFairPrice = maxOf(targetKmFare, targetMinFare)
        val fairPrice = (Math.round(rawFairPrice / 100.0) * 100).toInt()
        val diffPrice = fare - fairPrice

        // VERDICT WITH MAX PICKUP DISTANCE RULE
        val isPickupTooFar = pickupKm > (maxPickupKm + 0.05)

        val verdict = when {
            isPickupTooFar -> "REJECT_FAR"
            fare >= fairPrice && perKm >= minRateKm -> "ACCEPT"
            perKm >= (minRateKm * 0.85) -> "REGULAR"
            else -> "REJECT"
        }

        // 4. UPDATE DOCK IMMEDIATELY (DIRECT IN-PLACE VIEW UPDATE IN <10MS)
        mainHandler.post {
            updateDockWithOffer(
                totalKm = totalKm,
                totalMin = totalMin,
                pickupKm = pickupKm,
                perKm = perKm,
                perMin = perMin,
                fairPrice = fairPrice,
                diffPrice = diffPrice,
                verdict = verdict,
                isPickupTooFar = isPickupTooFar
            )
        }

        return true
    }

    private fun ensureDockAttached() {
        try {
            if (windowManager == null) {
                windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            }

            if (overlayView == null) {
                val inflater = LayoutInflater.from(this)
                overlayView = inflater.inflate(R.layout.overlay_bubble, null)

                val cardContainer = overlayView?.findViewById<View>(R.id.dockContainer)
                val bubbleContainer = overlayView?.findViewById<View>(R.id.bubbleContainer)
                val btnMinimize = overlayView?.findViewById<View>(R.id.btnMinimize)

                btnMinimize?.setOnClickListener {
                    toggleCollapse(true)
                }

                if (cardContainer != null) {
                    setupDragListener(cardContainer, isBubble = false)
                }
                if (bubbleContainer != null) {
                    setupDragListener(bubbleContainer, isBubble = true)
                }
            }

            if (!isOverlayAttached && overlayView != null) {
                val density = resources.displayMetrics.density
                val screenWidth = resources.displayMetrics.widthPixels
                val screenHeight = resources.displayMetrics.heightPixels
                val cardWidthPx = (142 * density).toInt()

                val params = WindowManager.LayoutParams(
                    cardWidthPx,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    // Position at upper-right where user drew the green rectangle
                    x = (screenWidth - cardWidthPx - (8 * density).toInt()).coerceAtLeast(0)
                    y = (screenHeight * 0.32f).toInt()
                }
                overlayLayoutParams = params

                windowManager?.addView(overlayView, params)
                isOverlayAttached = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupDragListener(view: View, isBubble: Boolean) {
        view.setOnTouchListener { v, event ->
            val params = overlayLayoutParams ?: return@setOnTouchListener false
            val density = resources.displayMetrics.density
            val screenWidth = resources.displayMetrics.widthPixels
            val screenHeight = resources.displayMetrics.heightPixels

            if (!isBubble) {
                val btnMin = overlayView?.findViewById<View>(R.id.btnMinimize)
                if (btnMin != null) {
                    val rect = Rect()
                    btnMin.getGlobalVisibleRect(rect)
                    if (rect.contains(event.rawX.toInt(), event.rawY.toInt())) {
                        if (event.action == MotionEvent.ACTION_UP) {
                            toggleCollapse(true)
                        }
                        return@setOnTouchListener true
                    }
                }
            }

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (Math.hypot(dx.toDouble(), dy.toDouble()) > 10f) {
                        isDragging = true
                    }
                    if (isDragging) {
                        val currentWidth = if (isBubble) (48 * density).toInt() else (142 * density).toInt()
                        params.x = (initialX + dx).toInt().coerceIn(0, (screenWidth - currentWidth).coerceAtLeast(0))
                        params.y = (initialY + dy).toInt().coerceIn(0, (screenHeight - 120).coerceAtLeast(0))
                        windowManager?.updateViewLayout(overlayView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        if (isBubble) {
                            toggleCollapse(false)
                        } else {
                            v.performClick()
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    fun toggleCollapse(collapse: Boolean) {
        try {
            isCollapsed = collapse
            val cardContainer = overlayView?.findViewById<View>(R.id.dockContainer)
            val bubbleContainer = overlayView?.findViewById<View>(R.id.bubbleContainer)
            val params = overlayLayoutParams ?: return
            val density = resources.displayMetrics.density
            val screenWidth = resources.displayMetrics.widthPixels

            if (collapse) {
                cardContainer?.visibility = View.GONE
                bubbleContainer?.visibility = View.VISIBLE
                val bubbleSize = (48 * density).toInt()
                params.width = bubbleSize
                params.height = bubbleSize
                // Dock to right or left edge nicely
                if (params.x > screenWidth / 2) {
                    params.x = screenWidth - bubbleSize - (6 * density).toInt()
                } else {
                    params.x = (6 * density).toInt()
                }
            } else {
                bubbleContainer?.visibility = View.GONE
                cardContainer?.visibility = View.VISIBLE
                val cardWidth = (142 * density).toInt()
                params.width = cardWidth
                params.height = WindowManager.LayoutParams.WRAP_CONTENT
                if (params.x + cardWidth > screenWidth) {
                    params.x = (screenWidth - cardWidth - (6 * density).toInt()).coerceAtLeast(0)
                }
            }
            windowManager?.updateViewLayout(overlayView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun updateDockWithOffer(
        totalKm: Double,
        totalMin: Int,
        pickupKm: Double,
        perKm: Int,
        perMin: Int,
        fairPrice: Int,
        diffPrice: Int,
        verdict: String,
        isPickupTooFar: Boolean
    ) {
        ensureDockAttached()

        // Auto-expand if collapsed so driver immediately sees offer analysis
        if (isCollapsed) {
            toggleCollapse(false)
        }

        val dockContainer = overlayView?.findViewById<View>(R.id.dockContainer)
        val bubbleContainer = overlayView?.findViewById<View>(R.id.bubbleContainer)
        val tvStatusBadge = overlayView?.findViewById<TextView>(R.id.tvStatusBadge)
        val tvBubbleDot = overlayView?.findViewById<TextView>(R.id.tvBubbleDot)
        val tvTotalKm = overlayView?.findViewById<TextView>(R.id.tvTotalKm)
        val tvTotalMin = overlayView?.findViewById<TextView>(R.id.tvTotalMin)
        val tvOriginDist = overlayView?.findViewById<TextView>(R.id.tvOriginDist)
        val tvPerKm = overlayView?.findViewById<TextView>(R.id.tvPerKm)
        val tvPerMin = overlayView?.findViewById<TextView>(R.id.tvPerMin)
        val tvFairPrice = overlayView?.findViewById<TextView>(R.id.tvFairPrice)
        val tvDiffPrice = overlayView?.findViewById<TextView>(R.id.tvDiffPrice)

        val strokeColor: Int
        when (verdict) {
            "ACCEPT" -> {
                strokeColor = Color.parseColor("#10b981")
                tvStatusBadge?.text = "🟢 ACEPTAR"
                tvStatusBadge?.setBackgroundColor(Color.parseColor("#80064e3b"))
                tvStatusBadge?.setTextColor(Color.parseColor("#34d399"))
            }
            "REJECT_FAR" -> {
                strokeColor = Color.parseColor("#FF3366")
                tvStatusBadge?.text = "🔴 RECHAZAR • LEJOS"
                tvStatusBadge?.setBackgroundColor(Color.parseColor("#80450a0a"))
                tvStatusBadge?.setTextColor(Color.parseColor("#f87171"))
            }
            "REJECT" -> {
                strokeColor = Color.parseColor("#FF3366")
                tvStatusBadge?.text = "🔴 RECHAZAR"
                tvStatusBadge?.setBackgroundColor(Color.parseColor("#80450a0a"))
                tvStatusBadge?.setTextColor(Color.parseColor("#f87171"))
            }
            else -> {
                strokeColor = Color.parseColor("#FFBE0B")
                tvStatusBadge?.text = "🟡 REGULAR"
                tvStatusBadge?.setBackgroundColor(Color.parseColor("#80451a03"))
                tvStatusBadge?.setTextColor(Color.parseColor("#fbbf24"))
            }
        }

        val density = resources.displayMetrics.density

        // Dynamic colored border on translucent background (#D9080C14)
        val borderBg = GradientDrawable().apply {
            setColor(Color.parseColor("#D9080C14"))
            setStroke((1.5f * density).toInt(), strokeColor)
            cornerRadius = 14f * density
        }
        dockContainer?.background = borderBg

        // Update bubble styling as well
        val bubbleBg = GradientDrawable().apply {
            setColor(Color.parseColor("#D9080C14"))
            setStroke((2f * density).toInt(), strokeColor)
            cornerRadius = 24f * density
        }
        bubbleContainer?.background = bubbleBg
        tvBubbleDot?.setTextColor(strokeColor)

        // Route Summary: Total Km & Total Min
        tvTotalKm?.text = String.format(Locale.US, "%.1f km", totalKm)
        tvTotalMin?.text = "$totalMin min"

        // Pickup / Origin distance
        val pickupFormatted = String.format(Locale.US, "%.1f km", pickupKm)
        tvOriginDist?.text = pickupFormatted
        if (isPickupTooFar) {
            tvOriginDist?.setTextColor(Color.parseColor("#FF3366"))
        } else {
            tvOriginDist?.setTextColor(Color.parseColor("#10b981"))
        }

        // Rates
        tvPerKm?.text = "\$$perKm"
        tvPerKm?.setTextColor(Color.parseColor("#00F5D4"))
        tvPerMin?.text = "\$$perMin/m"
        tvFairPrice?.text = "\$$fairPrice"
        tvFairPrice?.setTextColor(Color.parseColor("#FFBE0B"))

        // Balance
        if (diffPrice >= 0) {
            tvDiffPrice?.text = "EXTRA: +\$$diffPrice"
            tvDiffPrice?.setTextColor(Color.parseColor("#10b981"))
        } else {
            val absDiff = if (diffPrice < 0) -diffPrice else diffPrice
            tvDiffPrice?.text = "FALTAN: -\$$absDiff"
            tvDiffPrice?.setTextColor(Color.parseColor("#FF3366"))
        }

        // 15 seconds auto-reset back to zero/idle
        resetRunnable?.let { mainHandler.removeCallbacks(it) }
        resetRunnable = Runnable {
            resetDockToIdle()
        }
        mainHandler.postDelayed(resetRunnable!!, 15000)
    }

    fun resetDockToIdle() {
        try {
            ensureDockAttached()

            val dockContainer = overlayView?.findViewById<View>(R.id.dockContainer)
            val bubbleContainer = overlayView?.findViewById<View>(R.id.bubbleContainer)
            val tvStatusBadge = overlayView?.findViewById<TextView>(R.id.tvStatusBadge)
            val tvBubbleDot = overlayView?.findViewById<TextView>(R.id.tvBubbleDot)
            val tvTotalKm = overlayView?.findViewById<TextView>(R.id.tvTotalKm)
            val tvTotalMin = overlayView?.findViewById<TextView>(R.id.tvTotalMin)
            val tvOriginDist = overlayView?.findViewById<TextView>(R.id.tvOriginDist)
            val tvPerKm = overlayView?.findViewById<TextView>(R.id.tvPerKm)
            val tvPerMin = overlayView?.findViewById<TextView>(R.id.tvPerMin)
            val tvFairPrice = overlayView?.findViewById<TextView>(R.id.tvFairPrice)
            val tvDiffPrice = overlayView?.findViewById<TextView>(R.id.tvDiffPrice)

            val density = resources.displayMetrics.density

            // Neutral translucent border
            val neutralBg = GradientDrawable().apply {
                setColor(Color.parseColor("#D9080C14"))
                setStroke((1.5f * density).toInt(), Color.parseColor("#4D38BDF8"))
                cornerRadius = 14f * density
            }
            dockContainer?.background = neutralBg

            val neutralBubbleBg = GradientDrawable().apply {
                setColor(Color.parseColor("#D9080C14"))
                setStroke((2f * density).toInt(), Color.parseColor("#00F5D4"))
                cornerRadius = 24f * density
            }
            bubbleContainer?.background = neutralBubbleBg
            tvBubbleDot?.setTextColor(Color.parseColor("#00F5D4"))

            tvStatusBadge?.text = "⚪ KAPTOR • ESPERA"
            tvStatusBadge?.setBackgroundColor(Color.parseColor("#661E293B"))
            tvStatusBadge?.setTextColor(Color.parseColor("#94a3b8"))

            tvTotalKm?.text = "0.0 km"
            tvTotalMin?.text = "0 min"

            tvOriginDist?.text = "0.0 km"
            tvOriginDist?.setTextColor(Color.parseColor("#94a3b8"))

            tvPerKm?.text = "$0"
            tvPerMin?.text = "$0"
            tvFairPrice?.text = "$0"

            tvDiffPrice?.text = "BALANCE: --"
            tvDiffPrice?.setTextColor(Color.parseColor("#64748b"))

            currentOfferKey = ""
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun removeOverlayView() {
        try {
            resetRunnable?.let { mainHandler.removeCallbacks(it) }
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
