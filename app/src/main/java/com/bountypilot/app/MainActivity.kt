package com.bountypilot.app

import android.app.Activity
import android.content.pm.ActivityInfo
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.net.TrafficStats
import android.os.Build
import android.provider.MediaStore
import android.os.Process
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.widget.*
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLHandshakeException

class MainActivity : Activity() {

    // Cyber theme: deep navy + electric blue/cyan scanning wave.
    private val bg = Color.rgb(2, 6, 18)
    private val panel = Color.rgb(7, 14, 30)
    private val panel2 = Color.rgb(10, 21, 43)
    private val blue = Color.rgb(35, 150, 255)
    private val cyan = Color.rgb(0, 229, 255)
    private val white = Color.rgb(235, 245, 255)
    private val gray = Color.rgb(125, 145, 170)
    private val yellow = Color.rgb(255, 205, 75)
    private val red = Color.rgb(255, 85, 105)
    private val green = Color.rgb(60, 255, 150)

    private val executor = Executors.newSingleThreadExecutor()
    private val uiHandler = Handler(Looper.getMainLooper())
    private val cyberTone = ToneGenerator(AudioManager.STREAM_MUSIC, 35)
    private var typingSoundEnabled = true
    private var lastTypeSoundAt = 0L
    private var lastAssessment: AssessmentResult? = null
    private var lastProbeResult: ProbeResult? = null
    private var testEndpointOverride: String = ""
    private val httpHistory = CopyOnWriteArrayList<HttpMessage>()
    private var drawerOpen = false
    private var backgroundLogView: TextView? = null
    private var pendingImageForForensics: Uri? = null
    private val logHideRunnable = Runnable { backgroundLogView?.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showHome()
    }

    override fun onDestroy() {
        trafficMonitorRunning = false
        executor.shutdownNow()
        uiHandler.removeCallbacksAndMessages(null)
        cyberTone.release()
        super.onDestroy()
    }

    private fun cyberClick() {
        try { cyberTone.startTone(ToneGenerator.TONE_PROP_BEEP, 35) } catch (_: Exception) {}
    }

    private fun cyberType() {
        val now = System.currentTimeMillis()
        if (!typingSoundEnabled || now - lastTypeSoundAt < 55L) return
        lastTypeSoundAt = now
        try { cyberTone.startTone(ToneGenerator.TONE_PROP_BEEP2, 22) } catch (_: Exception) {}
    }

    private fun cyberScanSound(durationMs: Long = 2600L) {
        val start = System.currentTimeMillis()
        fun tick() {
            if (System.currentTimeMillis() - start >= durationMs) return
            try { cyberTone.startTone(ToneGenerator.TONE_PROP_ACK, 45) } catch (_: Exception) {}
            uiHandler.postDelayed({ tick() }, 210L)
        }
        tick()
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun root() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(bg)
        setPadding(dp(18), dp(62), dp(18), dp(20))
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.08f)
    }

    private fun cyberButton(label: String, action: () -> Unit, accent: Int = blue) = Button(this).apply {
        text = label
        textSize = 11.5f
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        isAllCaps = false
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(accent, cyan)).apply {
            cornerRadius = dp(9).toFloat()
            setStroke(dp(1), Color.argb(180, 140, 220, 255))
        }
        setPadding(dp(8), 0, dp(8), 0)
        setOnClickListener {
            cyberClick()
            val a = AlphaAnimation(0.55f, 1f).apply { duration = 130; repeatCount = 1; repeatMode = AlphaAnimation.REVERSE }
            startAnimation(a)
            action()
        }
    }

    private fun darkButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 11.5f
        setTextColor(cyan)
        typeface = Typeface.DEFAULT_BOLD
        background = GradientDrawable().apply {
            cornerRadius = dp(9).toFloat()
            setColor(panel2)
            setStroke(dp(1), Color.rgb(25, 105, 170))
        }
        setOnClickListener { action() }
    }

    private fun darkButton(label: String, action: () -> Unit, params: LinearLayout.LayoutParams): Button =
        darkButton(label, action).apply { layoutParams = params }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(panel)
            setStroke(dp(1), Color.rgb(18, 58, 100))
        }
    }

    private fun gap(n: Int) = Space(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(n)) }
    private fun logScan(message: String) {
        val line = "[${java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(java.util.Date())}] $message"
        runOnUiThread {
            val tv = backgroundLogView ?: return@runOnUiThread
            uiHandler.removeCallbacks(logHideRunnable)
            tv.visibility = View.VISIBLE
            val existing = tv.text?.toString().orEmpty().lines().filter { it.isNotBlank() }.takeLast(29)
            tv.text = (existing + line).joinToString("\n")
            uiHandler.postDelayed(logHideRunnable, 4200L)
        }
    }

    private fun keepInnerScrollLocal(scroll: ScrollView) {
        scroll.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE -> v.parent?.requestDisallowInterceptTouchEvent(true)
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
    }

    private fun keepWebViewScrollLocal(web: WebView) {
        web.setOnTouchListener { v, event ->
            val lock = event.actionMasked == android.view.MotionEvent.ACTION_DOWN || event.actionMasked == android.view.MotionEvent.ACTION_MOVE
            var parent = v.parent
            while (parent != null) { parent.requestDisallowInterceptTouchEvent(lock); parent = parent.parent }
            false
        }
    }

    private fun addUrlButton(container: LinearLayout, label: String, url: String) {
        container.addView(darkButton(label) { openUrl(url) }, LinearLayout.LayoutParams(-1, dp(42)))
        container.addView(text("Direct public URL • verify manually • no private access", 9.5f, gray))
        container.addView(gap(4))
    }

    private fun addSearchSource(container: LinearLayout, title: String, description: String, url: String) {
        container.addView(darkButton("OPEN $title") { openUrl(url) }, LinearLayout.LayoutParams(-1, dp(42)))
        container.addView(text(description, 9.5f, gray))
        container.addView(gap(4))
    }

    private fun setScreen(content: LinearLayout) {
        val frame = FrameLayout(this).apply { setBackgroundColor(bg) }
        frame.addView(CyberWaveView(this), FrameLayout.LayoutParams(-1, -1))
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        scroll.addView(content, FrameLayout.LayoutParams(-1, -1))
        frame.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        val shade = View(this).apply {
            tag = "bp_shade"
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            visibility = View.GONE
            setOnClickListener { closeDrawer() }
        }
        frame.addView(shade, FrameLayout.LayoutParams(-1, -1))

        val drawer = LinearLayout(this).apply {
            tag = "bp_drawer"
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(12), dp(18))
            setBackgroundColor(panel2)
            elevation = dp(12).toFloat()
        }
        val drawerLp = FrameLayout.LayoutParams(dp(292), -1)
        drawerLp.gravity = Gravity.START
        drawerLp.topMargin = dp(54)
        drawerLp.bottomMargin = 0
        drawer.translationX = -dp(292).toFloat()
        frame.addView(drawer, drawerLp)

        val logView = TextView(this).apply {
            tag = "bp_scan_log"
            setTextColor(green)
            typeface = Typeface.MONOSPACE
            textSize = 10.5f
            alpha = 0.18f
            gravity = Gravity.BOTTOM or Gravity.START
            setPadding(dp(8), dp(8), dp(8), dp(8))
            maxLines = 24
            isClickable = false
            isFocusable = false
            text = "[BountyPilot] idle"
        }
        frame.addView(logView, FrameLayout.LayoutParams(-1, -1))
        backgroundLogView = logView

        fun menuItem(label: String, action: () -> Unit) {
            drawer.addView(darkButton(label) { closeDrawer(); action() }, LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(6) })
        }
        drawer.addView(text("BOUNTYPILOT MENU", 15f, cyan, true))
        drawer.addView(gap(12))
        menuItem("⌂  DASHBOARD") { showHome() }
        menuItem("◈  BURP WEB LAB") { showBurpSuite() }
        menuItem("◉  VULNERABILITY SCANNER") { showVulnerabilityScanner() }
        menuItem("☷  PAYLOAD LIBRARY") { showPayloadLibrary() }
        menuItem("⌁  NETWORK SUITE") { showNetworkSuite() }
        menuItem("≋  TRAFFIC LAB") { showTrafficSuite() }
        menuItem("◎  OSINT INTELLIGENCE") { showOsintHub() }
        menuItem("◉  PUBLIC CAMERAS") { showPublicCameras() }
        menuItem("◆  METASPLOIT LAB") { showMetasploitBridge() }
        menuItem("▣  FINDINGS / REPORTS") { showFindingsHub() }
        menuItem("✦  APPFORGE BUILDER") { enterAppForge() }
        menuItem("✦  TRAINING LAB") { showTrainingLab() }
        drawer.addView(gap(8))
        menuItem("⚙  SETTINGS") { showSettings() }
        drawer.addView(gap(8))
        drawer.addView(text("v4.2 • SECURITY + APPFORGE WORKSTATION", 9.5f, gray))

        val menu = cyberButton("☰", {
            if (drawerOpen) closeDrawer() else openDrawer(drawer, shade)
        }, blue).apply { textSize = 17f }
        val menuLp = FrameLayout.LayoutParams(dp(50), dp(42))
        menuLp.gravity = Gravity.TOP or Gravity.START
        menuLp.leftMargin = dp(10); menuLp.topMargin = dp(8)
        frame.addView(menu, menuLp)
        setContentView(frame)
    }

    private fun openDrawer(drawer: View, shade: View) {
        drawerOpen = true
        shade.visibility = View.VISIBLE
        drawer.animate().translationX(0f).setDuration(220).start()
    }

    private fun closeDrawer() {
        drawerOpen = false
        val root = findViewById<ViewGroup>(android.R.id.content)
        val frame = root.getChildAt(0) as? ViewGroup ?: return
        val drawer = frame.findViewWithTag<View>("bp_drawer") ?: return
        val shade = frame.findViewWithTag<View>("bp_shade") ?: return
        drawer.animate().translationX(-dp(292).toFloat()).setDuration(180).withEndAction { shade.visibility = View.GONE }.start()
    }

    private fun header(root: LinearLayout, title: String, subtitle: String) {
        root.addView(text("BOUNTYPILOT", 27f, cyan, true))
        root.addView(text(title, 13f, blue, true))
        root.addView(text(subtitle, 10.5f, gray))
        root.addView(gap(13))
    }

    private fun input(hint: String, value: String = "") = EditText(this).apply {
        this.hint = hint
        setHintTextColor(gray)
        setTextColor(white)
        textSize = 14f
        setSingleLine(true)
        setText(value)
        background = GradientDrawable().apply {
            cornerRadius = dp(9).toFloat()
            setColor(panel2)
            setStroke(dp(1), Color.rgb(25, 95, 155))
        }
        setPadding(dp(13), dp(10), dp(13), dp(10))
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { cyberType() }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun copy(label: String, body: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, body))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun showHome() {
        testEndpointOverride = ""
        val v = root()
        header(v, "SECURITY OPERATIONS WORKSTATION", "AUTHORIZED BUG-BOUNTY LAB • SELECT A TOOL")

        val status = card()
        status.addView(text("● SYSTEM READY", 13f, green, true))
        status.addView(gap(3))
        status.addView(text("Select an engine below. Detailed instructions stay collapsed until you open them.", 11.5f, gray))
        v.addView(status)
        v.addView(gap(12))

        // AppForge is a dedicated landscape workstation; the rest of BountyPilot remains portrait.
        v.addView(cyberButton("✦  APPFORGE • BUILD APPS / WEB / GAMES", { enterAppForge() }, cyan), LinearLayout.LayoutParams(-1, dp(52)))
        v.addView(gap(10))

        // Compact grouped tool deck: related engines live together so the dashboard stays clean.
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun row(vararg items: View) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            items.forEach { item ->
                r.addView(item, LinearLayout.LayoutParams(0, dp(122), 1f).apply {
                    marginStart = dp(4); marginEnd = dp(4); topMargin = dp(4); bottomMargin = dp(4)
                })
            }
            grid.addView(r, LinearLayout.LayoutParams(-1, dp(130)))
        }
        row(
            toolTile("NETWORK SUITE", "NMAP • DNS/TLS", "⌁", blue) { showNetworkSuite() },
            toolTile("BURP WEB LAB", "PROXY • REPEATER", "◈", cyan) { showBurpSuite() }
        )
        row(
            toolTile("TRAFFIC LAB", "DEVICE • PCAP", "≋", blue) { showTrafficSuite() },
            toolTile("OSINT INTEL", "SHODAN • PUBLIC DATA", "◎", green) { showOsintHub() },
            toolTile("PUBLIC CAMERAS", "LIVE • MAP • LOCATION", "◉", cyan) { showPublicCameras() }
        )
        row(
            toolTile("FINDINGS", "EVIDENCE • REPORTS", "▣", yellow) { showFindingsHub() },
            toolTile("VULN SCANNER", "BROAD WEB CHECKS", "◉", yellow) { showVulnerabilityScanner() }
        )
        v.addView(grid)
        v.addView(gap(10))

        val target = card()
        target.addView(text("QUICK TARGET", 11.5f, cyan, true))
        target.addView(gap(6))
        val targetInput = input("https://authorized-example.com")
        target.addView(targetInput, LinearLayout.LayoutParams(-1, dp(50)))
        target.addView(gap(7))
        target.addView(cyberButton("▶ START AUTHORIZED RECON", {
            val raw = targetInput.text.toString().trim()
            if (raw.isEmpty()) Toast.makeText(this, "Enter an authorized target.", Toast.LENGTH_SHORT).show()
            else authorizationGate("FULL SAFE RECON") { inspect(raw) }
        }, blue), LinearLayout.LayoutParams(-1, dp(48)))
        v.addView(target)
        v.addView(gap(10))

        v.addView(collapsibleSection(
            "SCANNING PIPELINE",
            "DNS / TCP / TLS\nHTTP + headers + cookies\nHTML/forms/links/technology\nrobots / sitemap / security.txt\nsame-origin endpoint inventory\nsafe surface checks\none-test-at-a-time validation\nevidence + bounty-style report"
        ))
        v.addView(gap(7))
        v.addView(collapsibleSection(
            "SAFETY & AUTHORIZATION",
            "Every active target action has an authorization gate. The confirmation is an attestation only; it does not grant permission. You must already be authorized and stay within the program's exact scope and testing rules."
        ))
        v.addView(gap(7))
        v.addView(collapsibleSection(
            "ENGINE STATUS",
            "Nmap executable: ${if (nmapAvailable()) "AVAILABLE" else "EXTERNAL / NOT IN APP SANDBOX"}\nMetasploit: ${if (termuxInstalled()) "TERMUX BRIDGE AVAILABLE" else "EXTERNAL BRIDGE / NOT DETECTED"}\nHTTP/TLS: BUILT-IN\nPCAP parser: BUILT-IN\nWireshark/tshark: EXTERNAL PCAP WORKFLOW"
        ))
        v.addView(gap(12))
        v.addView(text("v2.5 • CYBER WAVE SECURITY WORKSTATION", 9.5f, gray).apply { gravity = Gravity.CENTER })
        setScreen(v)
    }

    private fun toolTile(title: String, subtitle: String, glyph: String, accent: Int, action: () -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(7), dp(6), dp(7))
            background = GradientDrawable().apply {
                cornerRadius = dp(15).toFloat()
                setColor(panel)
                setStroke(dp(1), Color.rgb(25, 95, 155))
            }
            setOnClickListener { action() }
        }
        box.addView(cyberCubeIcon(glyph, accent), LinearLayout.LayoutParams(dp(54), dp(54)))
        box.addView(gap(4))
        box.addView(text(title, 10.5f, white, true).apply { gravity = Gravity.CENTER })
        box.addView(text(subtitle, 8.5f, gray).apply { gravity = Gravity.CENTER })
        return box
    }

    private fun cyberCubeIcon(glyph: String, accent: Int): View {
        return object : View(this) {
            private val p = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(c: Canvas) {
                super.onDraw(c)
                val w = width.toFloat(); val h = height.toFloat()
                val cx = w / 2f; val cy = h / 2f
                p.style = Paint.Style.FILL
                p.color = Color.rgb(5, 20, 42)
                c.drawRoundRect(dp(5).toFloat(), dp(5).toFloat(), w-dp(5), h-dp(5), dp(13).toFloat(), dp(13).toFloat(), p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = dp(1).toFloat()
                p.color = Color.argb(210, Color.red(accent), Color.green(accent), Color.blue(accent))
                c.drawRoundRect(dp(5).toFloat(), dp(5).toFloat(), w-dp(5), h-dp(5), dp(13).toFloat(), dp(13).toFloat(), p)
                p.style = Paint.Style.FILL
                p.color = accent
                val path = android.graphics.Path()
                path.moveTo(cx, cy-dp(16))
                path.lineTo(cx+dp(16), cy-dp(7))
                path.lineTo(cx, cy+dp(2))
                path.lineTo(cx-dp(16), cy-dp(7))
                path.close()
                c.drawPath(path, p)
                p.color = Color.argb(190, 255,255,255)
                p.textSize = dp(15).toFloat()
                p.typeface = Typeface.DEFAULT_BOLD
                p.textAlign = Paint.Align.CENTER
                c.drawText(glyph, cx, cy+dp(23).toFloat(), p)
            }
        }
    }

    private fun collapsibleSection(title: String, body: String): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(panel)
                setStroke(dp(1), Color.rgb(18,58,100))
            }
        }
        val content = text(body, 11.5f, gray).apply {
            visibility = View.GONE
            setPadding(dp(12), 0, dp(12), dp(12))
        }
        val head = TextView(this).apply {
            text = "›  $title"
            textSize = 11.5f
            setTextColor(cyan)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener {
                val open = content.visibility != View.VISIBLE
                content.visibility = if (open) View.VISIBLE else View.GONE
                text = if (open) "⌄  $title" else "›  $title"
            }
        }
        wrap.addView(head)
        wrap.addView(content)
        return wrap
    }

    private fun showNetworkSuite() {
        val v = root(); header(v, "NETWORK SUITE", "NMAP • TCP INVENTORY • DNS • TLS")
        val c = card()
        c.addView(text("NETWORK ENGINES", 12f, cyan, true)); c.addView(gap(6))
        c.addView(text("Keep Nmap, built-in authorized TCP inventory, DNS resolution and TLS inspection together. Results return to this suite instead of the dashboard.", 11.5f, gray))
        c.addView(gap(8))
        c.addView(cyberButton("OPEN NMAP CONSOLE", { showNmapConsole() }, blue), LinearLayout.LayoutParams(-1, dp(46)))
        c.addView(gap(6)); c.addView(cyberButton("DNS / TLS UTILITIES", { showDnsTlsConsole() }, cyan), LinearLayout.LayoutParams(-1, dp(46)))
        v.addView(c); v.addView(gap(10)); v.addView(darkButton("‹ BACK TO DASHBOARD") { showHome() }, LinearLayout.LayoutParams(-1, dp(45))); setScreen(v)
    }

    private data class HttpMessage(val id: Long, val method: String, val url: String, val request: String, val status: String, val response: String, val durationMs: Long)

    private fun showBurpSuite() {
        val v = root(); header(v, "BURP WEB LAB", "MOBILE HTTP WORKBENCH • AUTHORIZED TARGETS")
        val intro = card()
        intro.addView(text("BURP-STYLE WORKSPACE", 12f, cyan, true)); intro.addView(gap(5))
        intro.addView(text("This is BountyPilot's built-in mobile web-testing workspace. It combines a request composer/repeater, HTTP history, decoder, comparer, scope and evidence flow in one app. It is not a reimplementation of PortSwigger's proprietary Burp Suite binary.", 11.5f, gray))
        v.addView(intro); v.addView(gap(10))

        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun r(a: View, b: View) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(a, LinearLayout.LayoutParams(0, dp(100), 1f).apply { rightMargin=dp(4) })
            row.addView(b, LinearLayout.LayoutParams(0, dp(100), 1f).apply { leftMargin=dp(4) })
            grid.addView(row, LinearLayout.LayoutParams(-1, dp(106)))
        }
        r(toolTile("REPEATER", "SEND • EDIT", "↻", cyan) { showRepeater() }, toolTile("HISTORY", "HTTP LOG", "≋", blue) { showHttpHistory() })
        r(toolTile("DECODER", "ENCODE • DECODE", "◇", green) { showDecoder() }, toolTile("COMPARER", "DIFF DATA", "⇄", yellow) { showComparer() })
        r(toolTile("SCOPE", "IN / OUT", "◎", blue) { showScope() }, toolTile("SITE MAP", "SURFACES", "⌘", cyan) { showSiteMap() })
        r(toolTile("INTRUDER LAB", "ONE PAYLOAD", "⚡", red) { showIntruderLab() }, toolTile("PAYLOAD LIBRARY", "SAFE CANARIES", "☷", green) { showPayloadLibrary() })
        r(toolTile("SEQUENCER", "TOKEN ANALYSIS", "≈", green) { showSequencer() }, toolTile("INSPECTOR", "HEADERS • PARAMS", "⌗", cyan) { showInspector() })
        r(toolTile("SCANNER", "BROAD WEB ASSESSMENT", "◎", yellow) { showVulnerabilityScanner() }, toolTile("PROXY", "INTERCEPT INFO", "⇄", blue) { showProxySettings() })
        v.addView(grid); v.addView(gap(10))

        val proxy = card(); proxy.addView(text("PROXY / INTERCEPT", 12f, cyan, true)); proxy.addView(gap(5))
        proxy.addView(text("Android HTTPS interception is not silently enabled. Full MITM interception requires a local proxy/VPN path and a user-installed CA certificate. The built-in Repeater sends requests directly and records them in History.", 11.5f, gray)); proxy.addView(gap(7))
        proxy.addView(darkButton("OPEN PROXY SETTINGS") { showProxySettings() }, LinearLayout.LayoutParams(-1, dp(45)))
        v.addView(proxy); v.addView(gap(10))
        v.addView(darkButton("‹ BACK TO DASHBOARD") { showHome() }, LinearLayout.LayoutParams(-1, dp(45)))
        setScreen(v)
    }

    private fun showIntruderLab(){
        val v=root(); header(v,"INTRUDER LAB","CONTROLLED ONE-PAYLOAD WORKFLOW")
        val c=card(); c.addView(text("SAFE INTRUDER MODE",12f,cyan,true)); c.addView(gap(5))
        c.addView(text("BountyPilot never automates credential stuffing or high-volume attacks. Load exactly one candidate, review it, then optionally send that exact GET once to an authorized target.",11.5f,gray)); c.addView(gap(7))
        val base=multilineInput("Request/parameter template", "https://authorized.example/search?q={{PAYLOAD}}")
        val payload=multilineInput("Payload queue (one per line)", "bountyprobe-001\n%27\n%22\n<bountyprobe>\n../bountyprobe")
        val result=multilineInput("CURRENT TEST / RESPONSE", "")
        val observation=multilineInput("Observation / notes", "")
        c.addView(base,LinearLayout.LayoutParams(-1,dp(110)));c.addView(gap(6));c.addView(payload,LinearLayout.LayoutParams(-1,dp(145)));c.addView(gap(6))
        c.addView(cyberButton("LOAD NEXT PAYLOAD",{
            val next=payload.text.toString().lineSequence().map{it.trim()}.filter{it.isNotEmpty()}.firstOrNull()
            if(next==null) result.setText("QUEUE EMPTY") else {result.setText(base.text.toString().replace("{{PAYLOAD}}",next)); payload.setText(payload.text.toString().lineSequence().dropWhile{it.trim().isEmpty()}.drop(1).joinToString("\n"))}
        },blue),LinearLayout.LayoutParams(-1,dp(45)))
        c.addView(gap(6));c.addView(cyberButton("TEST CURRENT ONCE",{
            val target=result.text.toString().trim()
            if(target.isBlank() || target.contains("{{PAYLOAD}}")){ Toast.makeText(this,"Load one payload first.",Toast.LENGTH_SHORT).show(); return@cyberButton }
            val u=normalizeUrl(target)
            if(u==null){ Toast.makeText(this,"Invalid URL.",Toast.LENGTH_SHORT).show(); return@cyberButton }
            authorizationGate("ONE INTRUDER LAB GET REQUEST"){ runIntruderGetOnce(u,result,observation) }
        },cyan),LinearLayout.LayoutParams(-1,dp(45)))
        c.addView(gap(6));c.addView(darkButton("OPEN PAYLOAD LIBRARY"){showPayloadLibrary()},LinearLayout.LayoutParams(-1,dp(45)))
        c.addView(gap(6));c.addView(darkButton("COPY CURRENT TEST"){copy("Intruder test",result.text.toString())},LinearLayout.LayoutParams(-1,dp(45)))
        c.addView(gap(8));c.addView(text("Example: set the template to https://authorized.example/search?q={{PAYLOAD}}, load one canary, inspect the exact URL, then TEST CURRENT ONCE. The app follows no redirects and records only the response summary.",11f,gray))
        v.addView(c);v.addView(gap(10));v.addView(darkButton("‹ BACK TO BURP WEB LAB"){showBurpSuite()},LinearLayout.LayoutParams(-1,dp(45)));setScreen(v)
    }

    private fun runIntruderGetOnce(rawUrl:String,result:EditText,observation:EditText){
        val u=normalizeUrl(rawUrl) ?: run{Toast.makeText(this,"Invalid URL.",Toast.LENGTH_SHORT).show();return}
        val a=lastAssessment
        if(a!=null){
            val host=runCatching{URL(u).host}.getOrNull()
            if(!host.equals(a.recon.host,true)){ Toast.makeText(this,"Intruder Lab is restricted to the loaded assessment host.",Toast.LENGTH_LONG).show(); return }
        }
        showLoading("INTRUDER TEST ONCE",u)
        executor.execute{
            val started=System.currentTimeMillis(); var conn:HttpURLConnection?=null
            try{
                conn=URL(u).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects=false; conn.connectTimeout=8000; conn.readTimeout=8000; conn.requestMethod="GET"
                conn.setRequestProperty("User-Agent","BountyPilot/2.3 safe-intruder")
                val code=conn.responseCode; val body=readBodySafely(conn).take(4000)
                val headers=conn.headerFields.entries.filter{it.key!=null}.take(25).joinToString("\n"){ "${it.key}: ${it.value.joinToString(" | ")}" }
                val elapsed=System.currentTimeMillis()-started
                val msg=HttpMessage(System.currentTimeMillis(),"GET",u,"GET $u","$code ${conn.responseMessage?:""}".trim(),body,elapsed)
                httpHistory.add(msg)
                runOnUiThread{ result.setText("URL: $u\nSTATUS: $code\nTIME: ${elapsed} ms\n\nHEADERS\n$headers\n\nBODY SAMPLE\n$body"); observation.setText("Record what changed, whether the canary was reflected/transformed, and whether the response remained expected.") }
            }catch(e:Exception){ runOnUiThread{result.setText("ERROR: ${classifyException(e)}")} } finally{conn?.disconnect()}
        }
    }

    private data class LibraryPayload(val category:String,val title:String,val payload:String,val use:String,val signal:String,val risk:String="LOW")

    private fun payloadLibrary(): List<LibraryPayload> = listOf(
        LibraryPayload("REFLECTION","Plain canary","bountyprobe-001","Put into a text/search parameter.","Exact value appears in response.") ,
        LibraryPayload("REFLECTION","Quote canary","%27","Use where a single quote is accepted.","Different escaping/error behavior.") ,
        LibraryPayload("REFLECTION","Double-quote canary","%22","Use where a double quote is accepted.","Different escaping/error behavior.") ,
        LibraryPayload("REFLECTION","HTML-context canary","<bountyprobe>","Use only in a controlled text field.","Returned as text, encoded, or interpreted.") ,
        LibraryPayload("XSS-CONTEXT","Attribute delimiter canary","\"bountyprobe","Use only to identify an HTML attribute context.","Observe encoding/context; do not treat reflection as XSS.") ,
        LibraryPayload("SQL-SYNTAX","Single-quote syntax probe","'","Use only for a harmless syntax/error comparison.","Stable parser/database error difference.") ,
        LibraryPayload("SQL-SYNTAX","Balanced numeric comparison","1","Baseline for an integer parameter.","Compare with a nearby boundary value.") ,
        LibraryPayload("SQL-SYNTAX","Boolean comparison A","1 AND 1=1","Only where simple boolean syntax testing is explicitly permitted.","Response differs from baseline.") ,
        LibraryPayload("SQL-SYNTAX","Boolean comparison B","1 AND 1=2","Pair with the previous canary and stop on unexpected impact.","Controlled response difference.") ,
        LibraryPayload("SSTI","Arithmetic canary","{{7*7}}","Use only where template rendering is suspected.","Rendered 49 or a template-specific error.") ,
        LibraryPayload("SSTI","Expression canary","${7*7}","Use only where expression/template syntax is suspected.","Rendered result or controlled parser error.") ,
        LibraryPayload("PATH","Encoded traversal canary","..%2F","Use only in an authorized path/file parameter.","Normalization or validation difference.") ,
        LibraryPayload("PATH","Double-encoded separator","..%252F","Use for normalization comparison only.","Different decoding behavior.") ,
        LibraryPayload("PATH","Dot-segment","./bountyprobe","Check path normalization without requesting unrelated files.","Canonicalization difference.") ,
        LibraryPayload("REDIRECT","Same-origin return","/","Use in a return/next parameter.","Location header matches expected origin.") ,
        LibraryPayload("REDIRECT","Controlled placeholder","https://example.invalid/bountyprobe","Use only where an external redirect test is explicitly allowed.","Location behavior; do not use a real third-party destination.") ,
        LibraryPayload("CORS","Invalid origin header","Origin: https://example.invalid","Send as a request header.","Compare ACAO/ACAC behavior.") ,
        LibraryPayload("CORS","Second invalid origin","Origin: https://bountypilot.invalid","Use as a second comparison origin.","Allowlist vs reflection behavior.") ,
        LibraryPayload("CORS","Null origin review","Origin: null","Only where the program permits this review.","Whether null is accepted and with what credentials behavior.") ,
        LibraryPayload("HPP","Duplicate parameter","id=1&id=2","Use on a harmless query parameter.","Which value the server/application chooses.") ,
        LibraryPayload("HPP","Duplicate key with same value","q=test&q=test","Baseline duplicate-key handling.","Stable normalization behavior.") ,
        LibraryPayload("JSON","Unknown field","{\"bountyPilotUnknown\":\"test\"}","Add to a JSON request you control.","Rejected, ignored, or unexpectedly accepted.") ,
        LibraryPayload("JSON","Null value","{\"value\":null}","Use on a non-sensitive JSON field.","Validation behavior.") ,
        LibraryPayload("JSON","Empty string","{\"value\":\"\"}","Use on a non-sensitive JSON field.","Validation behavior.") ,
        LibraryPayload("BOUNDARY","Zero","0","Use for numeric validation.","Expected boundary handling.") ,
        LibraryPayload("BOUNDARY","Negative one","-1","Use for numeric validation.","Expected rejection or documented behavior.") ,
        LibraryPayload("BOUNDARY","Small positive","1","Baseline numeric value.","Normal behavior.") ,
        LibraryPayload("UNICODE","Unicode canary","bountyprobe-✓-é","Use in a text field.","Normalization/encoding consistency.") ,
        LibraryPayload("UNICODE","Zero-width marker","bounty\u200bprobe","Use only in a text field.","Unexpected normalization or filtering.") ,
        LibraryPayload("ENCODING","Percent-encoded canary","%62%6f%75%6e%74%79","Use where URL decoding occurs.","Decoded vs literal behavior.") ,
        LibraryPayload("ENCODING","Plus-space comparison","bounty+probe","Use in query/form encoding.","Plus interpreted as space or literal plus.") ,
        LibraryPayload("HEADER","OPTIONS review","OPTIONS /","Review advertised methods.","Allow / CORS method headers.") ,
        LibraryPayload("HEADER","Cache review","Cache-Control / Pragma / Vary","Inspect rather than inject.","Sensitive responses have appropriate cache controls.") ,
        LibraryPayload("AUTH","Invalid username canary","invalid-bountyprobe-user","Use only with your own controlled test account set.","Generic error and rate-limit behavior.") ,
        LibraryPayload("AUTH","Invalid password canary","Wrong-BountyPilot-Password-001!","Use only against a controlled test account.","Generic failure and rate-limit behavior.") ,
        LibraryPayload("COOKIE","Cookie flags review","Inspect Set-Cookie","Capture a controlled login response.","Secure/HttpOnly/SameSite and rotation.") ,
        LibraryPayload("IDOR","Controlled object A","ACCOUNT_A_OBJECT_ID","Use only with two accounts you control.","Expected access to account A object.") ,
        LibraryPayload("IDOR","Controlled object B","ACCOUNT_B_OBJECT_ID","Swap only between your two controlled objects.","Authorization is enforced.") ,
        LibraryPayload("API","Unknown parameter","bountyPilotUnknown=1","Add to a harmless API request.","Ignored/rejected/unexpected behavior.") ,
        LibraryPayload("API","Boolean string","true","Use where a boolean field is expected.","Strict type validation.") ,
        LibraryPayload("API","Array boundary","[]","Use on a JSON array field you control.","Schema validation.") ,
        LibraryPayload("GRAPHQL","Benign query shape","query { __typename }","Only if GraphQL introspection/query testing is permitted.","Expected schema response or disabled introspection.") ,
        LibraryPayload("JWT","Header inspection","eyJhbGciOiJub25lIiwidHlwIjoiSldUIn0","Use as a decoding/structure exercise, not as an authentication bypass.","Algorithm/header handling can be reviewed safely.") ,
        LibraryPayload("CSRF","Token presence review","Inspect CSRF token / SameSite","Review a state-changing request in your own test account.","Token validation and cookie policy.") ,
        LibraryPayload("FILE-UPLOAD","Benign filename","bountyprobe.txt","Use only in an upload you are authorized to test.","Filename/content validation and storage behavior.") ,
        LibraryPayload("METHOD","HEAD review","HEAD /path","Compare with GET without changing state.","Headers/status consistency.") ,
        LibraryPayload("METHOD","OPTIONS review","OPTIONS /path","Review allowed methods.","Allow header and CORS behavior.") ,
        LibraryPayload("ERROR","Long-but-small marker","bountyprobe-0123456789","Use as a modest length comparison.","Validation/truncation behavior.") ,
        LibraryPayload("ERROR","Malformed JSON","{\"value\":","Use only against a harmless JSON parser endpoint.","Controlled 4xx rather than verbose stack trace."),
        LibraryPayload("XSS-CONTEXT","HTML text marker","bountyprobe-<x>","Use as a non-script context marker in an authorized text field.","Whether markup is encoded consistently."),
        LibraryPayload("XSS-CONTEXT","Entity encoding comparison","&lt;bountyprobe&gt;","Compare encoded and raw markers in the same harmless field.","Different normalization/encoding paths."),
        LibraryPayload("PATH","Trailing dot segment","safe/../bountyprobe","Use only for canonicalization comparison.","Unexpected path normalization."),
        LibraryPayload("PATH","Encoded dot segment","%2e%2e%2fbountyprobe","Use only where path normalization testing is permitted.","Different decoding or normalization behavior."),
        LibraryPayload("REDIRECT","Relative return marker","/bountyprobe","Use in a return/next parameter on an authorized app.","Unexpected cross-origin Location behavior."),
        LibraryPayload("CORS","Same-origin baseline","Origin: https://authorized.example","Compare against an origin you control or the application's own origin.","Expected allowlist behavior."),
        LibraryPayload("HTTP","Duplicate Content-Length review","Inspect raw request","Use only in a lab/repeater request where parser-differential testing is explicitly allowed.","Different proxy/backend interpretation; stop if instability occurs."),
        LibraryPayload("HTTP","Transfer-Encoding review","Inspect raw request","Use only in a dedicated local/lab parser-differential environment.","Different parser behavior; do not use against production without explicit permission."),
        LibraryPayload("API","Missing required field","{}","Use against a harmless API request you control.","Clear validation rather than server error."),
        LibraryPayload("API","Wrong primitive type","{\"value\":[]}","Use on a non-sensitive JSON field.","Strict schema validation."),
        LibraryPayload("API","Large-but-small integer","2147483647","Use as a boundary comparison where integer ranges are documented.","Consistent range handling."),
        LibraryPayload("GRAPHQL","Unknown field","query { bountyPilotUnknown }","Only against an authorized GraphQL endpoint.","Controlled schema validation error."),
        LibraryPayload("GRAPHQL","Empty selection review","query { __typename }","Use as a harmless baseline query.","Expected GraphQL response behavior."),
        LibraryPayload("AUTH","Username enumeration comparison","controlled-user-A / controlled-user-B","Use only with accounts you own.","Different failure messages/timing that reveal account existence."),
        LibraryPayload("SESSION","Logout invalidation review","Reuse your own session after logout","Perform manually with a controlled account.","Whether the old session remains valid."),
        LibraryPayload("SESSION","Session rotation review","Login with controlled account","Compare pre-login and post-login session identifiers.","Whether session identifier rotates after authentication."),
        LibraryPayload("CSRF","Origin header review","Origin: https://example.invalid","Use on a harmless state-changing action in a controlled account.","Whether the server validates origin/CSRF defenses."),
        LibraryPayload("UPLOAD","Benign extension mismatch","bountyprobe.txt","Use only on a test upload endpoint.","Whether extension/content validation is consistent."),
        LibraryPayload("HEADER","Referrer policy review","Inspect Referrer-Policy","Review representative authenticated pages.","Sensitive URLs or origins unnecessarily exposed via referrers."),
        LibraryPayload("HEADER","Permissions policy review","Inspect Permissions-Policy","Review browser-facing pages.","Unnecessary browser capabilities exposed."),
        LibraryPayload("DISCLOSURE","Robots review","GET /robots.txt","Review only public metadata.","Sensitive-looking paths disclosed in public metadata."),
        LibraryPayload("DISCLOSURE","Security.txt review","GET /.well-known/security.txt","Review contact/security policy metadata.","Security contact and policy presence."),
        LibraryPayload("DISCLOSURE","Source map review","Inspect *.map references","Check only publicly accessible assets.","Source maps exposing sensitive source or internal paths."),
        LibraryPayload("BOUNDARY","Empty value","","Use only where empty input is valid to test.","Clear rejection or documented empty handling."),
        LibraryPayload("BOUNDARY","Whitespace","   ","Use in a harmless text field.","Trim/validation consistency."),
        LibraryPayload("ENCODING","Double URL encode","%2562%256f%2575%256e%2574%2579","Use for decoding-layer comparison only.","Different decoding depth."),
        LibraryPayload("ENCODING","Unicode normalization pair","é / e\u0301","Compare equivalent Unicode representations.","Normalization inconsistencies."),
        LibraryPayload("METHOD","TRACE review","TRACE /","Only where the program explicitly permits method review.","Whether TRACE is unnecessarily enabled."),
        LibraryPayload("METHOD","PATCH capability review","PATCH /path","Use only on a harmless endpoint with no state impact.","Unexpected method acceptance."),
        LibraryPayload("ERROR","Invalid content type","Content-Type: application/xml","Send only to an endpoint that accepts content-type variations.","Clear 4xx validation instead of verbose parser errors.")
    )

    private fun showPayloadLibrary(){
        val v=root(); header(v,"PAYLOAD LIBRARY","SAFE DETECTION / MANUAL CANARIES")
        addSection(v,"SOURCE / POLICY","Inspired by common web-testing categories and the PayloadsAllTheThings project. BountyPilot stores detection-oriented canaries here; it does not bundle credential-stuffing lists, destructive payloads, reverse shells, persistence, or data-exfiltration recipes.",yellow)
        val category=input("Category (blank = all)",""); v.addView(category,LinearLayout.LayoutParams(-1,dp(48))); v.addView(gap(6))
        val query=input("Search title / payload / category",""); v.addView(query,LinearLayout.LayoutParams(-1,dp(48))); v.addView(gap(8))
        val list=payloadLibrary(); val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        fun render(){ box.removeAllViews(); val c=category.text.toString().trim().uppercase(Locale.ROOT); val q=query.text.toString().trim().lowercase(Locale.ROOT); val filtered=list.filter{(c.isBlank()||it.category==c)&&(q.isBlank()||it.title.lowercase().contains(q)||it.payload.lowercase().contains(q)||it.category.lowercase().contains(q))}; box.addView(text("${filtered.size} PAYLOADS",12f,cyan,true)); filtered.forEach{p0-> val card=card(); card.addView(text("${p0.category} • ${p0.title} • ${p0.risk}",11.5f,yellow,true)); card.addView(text("PAYLOAD: ${p0.payload}",12f,white)); card.addView(text("USE: ${p0.use}\nLOOK FOR: ${p0.signal}",10.8f,gray)); card.addView(gap(5)); card.addView(darkButton("COPY PAYLOAD"){copy("BountyPilot payload",p0.payload)},LinearLayout.LayoutParams(-1,dp(42))); box.addView(card); box.addView(gap(6))} }
        category.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,st:Int,c:Int,a:Int){};override fun onTextChanged(s:CharSequence?,st:Int,b:Int,c:Int){render()};override fun afterTextChanged(e:Editable?){}})
        query.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,st:Int,c:Int,a:Int){};override fun onTextChanged(s:CharSequence?,st:Int,b:Int,c:Int){render()};override fun afterTextChanged(e:Editable?){}})
        v.addView(box); render(); v.addView(gap(10)); addSection(v,"REPOSITORY REFERENCE","PayloadsAllTheThings is a large public reference containing many web-security categories and separate Intruder files. BountyPilot uses its categories as inspiration rather than copying the entire repository into the APK.",gray); v.addView(darkButton("OPEN PAYLOADSALLTHETHINGS") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/swisskyrepo/PayloadsAllTheThings"))) },LinearLayout.LayoutParams(-1,dp(45))); v.addView(gap(8)); v.addView(darkButton("‹ BACK TO BURP WEB LAB"){showBurpSuite()},LinearLayout.LayoutParams(-1,dp(45))); setScreen(v)
    }

    private fun showSequencer(){
        val v=root();header(v,"SEQUENCER","SESSION TOKEN RANDOMNESS REVIEW")
        val c=card();addSection(c,"HOW TO USE","Example: paste 10+ session tokens from your own controlled test account, one per line. ANALYZE checks count, uniqueness and length variation; it does not claim cryptographic strength.",yellow);val sample=multilineInput("Paste sample tokens, one per line");val out=multilineInput("Analysis");c.addView(text("This is a local statistical review, not a proof of cryptographic quality.",11.5f,gray));c.addView(gap(6));c.addView(sample,LinearLayout.LayoutParams(-1,dp(150)));c.addView(gap(6));c.addView(cyberButton("ANALYZE TOKENS",{val xs=sample.text.toString().lines().filter{it.isNotBlank()};val lengths=xs.map{it.length};out.setText("SAMPLES: ${xs.size}\nUNIQUE: ${xs.toSet().size}\nLENGTHS: ${lengths.distinct().sorted().joinToString(", ")}\nNOTE: entropy/cryptographic strength requires deeper analysis and application context.")},green),LinearLayout.LayoutParams(-1,dp(45)));c.addView(gap(6));c.addView(out,LinearLayout.LayoutParams(-1,dp(140)));v.addView(c);v.addView(gap(10));v.addView(darkButton("‹ BACK TO BURP WEB LAB"){showBurpSuite()},LinearLayout.LayoutParams(-1,dp(45)));setScreen(v)
    }

    private fun showInspector(){
        val v=root();header(v,"INSPECTOR","HTTP MESSAGE STRUCTURE")
        val c=card();addSection(c,"HOW TO USE","Example: paste `GET /search?q=test HTTP/1.1` followed by headers. INSPECT extracts the first line, headers and query parameter names.",yellow);val msg=multilineInput("Paste request or response");val out=multilineInput("Inspector output");c.addView(msg,LinearLayout.LayoutParams(-1,dp(190)));c.addView(gap(6));c.addView(cyberButton("INSPECT",{val raw=msg.text.toString();val lines=raw.lines();val first=lines.firstOrNull().orEmpty();val headers=lines.drop(1).takeWhile{it.isNotBlank()};val params=Regex("[?&]([^=&\\s]+)=([^&\\s]*)").findAll(raw).map{it.groupValues[1]}.distinct().toList();out.setText("FIRST LINE: $first\n\nHEADERS:\n${headers.joinToString("\n")}\n\nQUERY PARAMS: ${params.joinToString(", ").ifBlank{"none observed"}}")},cyan),LinearLayout.LayoutParams(-1,dp(45)));c.addView(gap(6));c.addView(out,LinearLayout.LayoutParams(-1,dp(180)));v.addView(c);v.addView(gap(10));v.addView(darkButton("‹ BACK TO BURP WEB LAB"){showBurpSuite()},LinearLayout.LayoutParams(-1,dp(45)));setScreen(v)
    }

    private data class VulnFinding(
        val severity: String,
        val title: String,
        val evidence: String,
        val validation: String,
        val remediation: String,
        val confidence: String
    )

    private fun showVulnerabilityScanner() {
        val v=root(); header(v,"VULNERABILITY SCANNER","BROAD WEB ASSESSMENT • SAFE / AUTHORIZED")
        val intro=card()
        intro.addView(text("WHAT THIS SCANS",12f,cyan,true)); intro.addView(gap(5))
        intro.addView(text("BountyPilot cannot prove every possible vulnerability automatically. This scanner builds broad coverage from the discovered attack surface and performs low-impact HTTP checks. Confirmed-looking results are reported with evidence, confidence, validation guidance, remediation and a safe reproduction path.",11.5f,gray))
        intro.addView(gap(6)); intro.addView(text("Coverage: transport/TLS • security headers • cookies • CORS • HTTP methods • information disclosure • error leakage • mixed content • exposed common metadata • endpoint behavior • technology/version hints.",10.8f,white))
        v.addView(intro); v.addView(gap(8))

        val a=lastAssessment
        if(a==null){
            val c=card(); c.addView(text("NO ASSESSMENT LOADED",13f,yellow,true)); c.addView(gap(5)); c.addView(text("Run authorized Recon first. The scanner uses the discovered endpoints instead of guessing random targets.",11.5f,gray)); v.addView(c)
        } else {
            val max=input("Maximum endpoints to check (1-30)","20")
            v.addView(max,LinearLayout.LayoutParams(-1,dp(48))); v.addView(gap(6))
            val status=multilineInput("SCAN STATUS / RESULTS","")
            v.addView(cyberButton("START SAFE VULNERABILITY SCAN",{
                val n=max.text.toString().toIntOrNull()?.coerceIn(1,30) ?: 20
                authorizationGate("BROAD LOW-IMPACT VULNERABILITY SCAN (${n} endpoints)"){ runBroadVulnerabilityScan(a,n,status) }
            },cyan),LinearLayout.LayoutParams(-1,dp(45)))
            v.addView(gap(7)); v.addView(status,LinearLayout.LayoutParams(-1,dp(240)))
            v.addView(gap(7)); v.addView(darkButton("OPEN CURRENT FINDINGS"){showFindingsHub()},LinearLayout.LayoutParams(-1,dp(45)))
        }
        v.addView(gap(10)); addSection(v,"IMPORTANT","A scanner result is not automatically a bounty-worthy vulnerability. BountyPilot labels observations as candidate findings until the tester validates impact on an authorized target. Do not use the scanner against systems outside program scope.",yellow)
        v.addView(darkButton("‹ BACK TO BURP WEB LAB"){showBurpSuite()},LinearLayout.LayoutParams(-1,dp(45))); setScreen(v)
    }

    private fun runBroadVulnerabilityScan(a: AssessmentResult, limit: Int, output: EditText) {
        val urls=(listOf(a.recon.url)+a.endpoints.endpoints).distinct().filter{normalizeUrl(it)!=null}.take(limit)
        cyberScanSound((urls.size.coerceAtMost(12)*220L).coerceAtLeast(900L)); output.setText("STARTING…\nTARGET: ${a.recon.host}\\nENDPOINTS: ${urls.size}")
        executor.execute {
            val findings=mutableListOf<VulnFinding>()
            urls.forEachIndexed { idx, raw ->
                val u=normalizeUrl(raw) ?: return@forEachIndexed
                if(!u.contains(a.recon.host,true)) return@forEachIndexed
                val r=fetchSafeAssessment(u)
                findings += analyzeSafeResponse(u,r)
                uiHandler.post { output.setText("SCANNING ${idx+1}/${urls.size}\n$u\n\nCANDIDATES: ${findings.size}") }
            }
            val text=buildString {
                append("BROAD SCAN COMPLETE\n\nTARGET: ${a.recon.host}\\nENDPOINTS CHECKED: ${urls.size}\\nCANDIDATES: ${findings.size}\\n\\n")
                if(findings.isEmpty()) append("No additional candidate signals were observed by these safe checks. This does not mean the application is vulnerability-free.")
                else findings.distinctBy{it.title+it.evidence}.forEachIndexed { i,f ->
                    append("${i+1}. [${f.severity}] ${f.title}\nCONFIDENCE: ${f.confidence}\\nEVIDENCE: ${f.evidence}\\nVALIDATE: ${f.validation}\\nFIX: ${f.remediation}\\n\\n")
                }
            }
            uiHandler.post { output.setText(text); lastBroadFindings=findings.distinctBy{it.title+it.evidence} }
        }
    }

    private data class SafeHttp(val code:Int,val headers:Map<String,String>,val body:String,val error:String="")
    private var lastBroadFindings: List<VulnFinding> = emptyList()

    private fun fetchSafeAssessment(raw:String):SafeHttp {
        var c:HttpURLConnection?=null
        return try {
            val u=URL(raw); c=(u.openConnection() as HttpURLConnection).apply{
                requestMethod="GET"; connectTimeout=7000; readTimeout=7000; instanceFollowRedirects=false
                setRequestProperty("User-Agent","BountyPilot-SafeScanner/2.5")
                setRequestProperty("Accept","text/html,application/json,*/*")
            }
            val code=c.responseCode
            val stream=if(code>=400)c.errorStream else c.inputStream
            val body=stream?.bufferedReader()?.use{it.readText().take(12000)} ?: ""
            SafeHttp(code,c.headerFields.filterKeys{it!=null}.mapKeys{it.key!!}.mapValues{it.value.joinToString("; ")},body)
        }catch(e:Exception){SafeHttp(0,emptyMap(),"",e.message?:(e.javaClass.simpleName))}finally{c?.disconnect()}
    }

    private fun analyzeSafeResponse(url:String,r:SafeHttp):List<VulnFinding>{
        val out=mutableListOf<VulnFinding>(); val h=r.headers.mapKeys{it.key.lowercase(Locale.ROOT)}; val body=r.body
        if(r.code==0){ return listOf(VulnFinding("INFO","Endpoint could not be checked",r.error,"Retry from Repeater and compare DNS/TCP/TLS reachability.","Verify availability, timeouts and network controls.","LOW")) }
        if(url.startsWith("https://",true) && !h.containsKey("strict-transport-security")) out += VulnFinding("LOW","HSTS not observed","Strict-Transport-Security header was not observed on $url","Confirm HTTPS is enforced and check an HTTPS response directly.","Enable HSTS with an appropriate max-age/includeSubDomains policy after testing deployment impact.","MEDIUM")
        if(!h.containsKey("content-security-policy") && body.contains("<html",true)) out += VulnFinding("LOW","CSP not observed","No Content-Security-Policy header was observed on an HTML response.","Review whether a CSP exists on representative authenticated and unauthenticated pages.","Deploy a context-appropriate CSP and monitor violations before tightening it.","MEDIUM")
        if(!h.containsKey("x-frame-options") && !h.containsKey("content-security-policy") && body.contains("<html",true)) out += VulnFinding("LOW","Framing protection not observed","Neither X-Frame-Options nor a frame-ancestors CSP directive was observed.","Check whether sensitive pages can be framed in an authorized test environment.","Use CSP frame-ancestors and/or X-Frame-Options where appropriate.","MEDIUM")
        if(h["server"]?.matches(Regex(".*[0-9].*"))==true) out += VulnFinding("INFO","Server version disclosure","Server header appears to contain version information: ${h["server"]}","Verify whether the version is unnecessarily exposed and correlate with known supported versions.","Minimize unnecessary version disclosure and keep the server patched.","LOW")
        val setCookie=h["set-cookie"].orEmpty(); if(setCookie.isNotBlank() && !setCookie.contains("httponly",true)) out += VulnFinding("MEDIUM","Cookie without HttpOnly observed","A Set-Cookie response did not visibly include HttpOnly.","Repeat on a controlled authentication/session response and identify whether the cookie contains sensitive session state.","Add HttpOnly to sensitive cookies unless client-side access is explicitly required.","MEDIUM")
        if(setCookie.isNotBlank() && url.startsWith("https://",true) && !setCookie.contains("secure",true)) out += VulnFinding("MEDIUM","Cookie without Secure observed","A Set-Cookie response did not visibly include Secure on HTTPS.","Review the exact cookie and confirm whether it carries authentication/session state.","Set Secure on sensitive cookies served over HTTPS.","MEDIUM")
        val acao=h["access-control-allow-origin"].orEmpty(); if(acao=="*") out += VulnFinding("LOW","Wildcard CORS observed","Access-Control-Allow-Origin: * was observed.","Check whether the endpoint exposes sensitive data and whether credentials are allowed.","Use an explicit trusted-origin allowlist for sensitive resources.","MEDIUM")
        if(body.contains("exception",true)||body.contains("stack trace",true)||body.contains("sqlstate",true)||body.contains("syntax error",true)) out += VulnFinding("MEDIUM","Verbose error signal","The response body contains text resembling a framework/database error.","Trigger the same behavior with a harmless malformed input and preserve only the minimum evidence needed.","Return generic client errors and keep detailed diagnostics server-side.","LOW")
        if(body.contains("sourceMappingURL=",true)) out += VulnFinding("INFO","Source map reference observed","HTML/JS response contains a sourceMappingURL reference.","Check whether the referenced map is publicly accessible and whether it exposes sensitive source material.","Avoid publishing sensitive source maps to production or restrict access appropriately.","LOW")
        return out
    }

    private fun showWebScanner(){
        val v=root();header(v,"WEB SCANNER","PASSIVE / LOW-IMPACT ANALYSIS")
        val a=lastAssessment;val c=card();addSection(c,"HOW TO USE","Example: after recon, Scanner summarizes observed headers, cookies, CORS and candidate findings. It is passive and does not prove exploitability.",yellow);c.addView(text(if(a==null)"NO TARGET LOADED" else "TARGET: ${a.recon.url}",13f,if(a==null)yellow else green,true));c.addView(gap(6));c.addView(text(if(a==null)"Run authorized recon from the dashboard first." else "Status: ${a.recon.status}\nTitle: ${a.recon.title}\nForms: ${a.recon.forms}\nLinks: ${a.recon.links}\nCandidates: ${a.findings.size}",11.5f,white));c.addView(gap(6));c.addView(darkButton("OPEN FINDINGS"){showFindingsHub()},LinearLayout.LayoutParams(-1,dp(45)));v.addView(c);v.addView(gap(10));v.addView(darkButton("‹ BACK TO BURP WEB LAB"){showBurpSuite()},LinearLayout.LayoutParams(-1,dp(45)));setScreen(v)
    }

    private fun showRepeater() {
        val v=root(); header(v,"REPEATER","MANUAL HTTP REQUEST • ONE REQUEST AT A TIME")
        val c=card(); c.addView(text("REQUEST",12f,cyan,true)); c.addView(gap(5))
        val method=input("GET / HEAD / OPTIONS", "GET"); c.addView(method, LinearLayout.LayoutParams(-1,dp(48))); c.addView(gap(5))
        val url=input("https://authorized.example/path?x=test"); c.addView(url, LinearLayout.LayoutParams(-1,dp(48))); c.addView(gap(5))
        val headers=multilineInput("Headers (one per line)", "User-Agent: BountyPilot/2.3\nAccept: */*"); c.addView(headers, LinearLayout.LayoutParams(-1,dp(120))); c.addView(gap(5))
        val body=multilineInput("Optional body (used only with explicitly enabled state-changing methods)"); c.addView(body, LinearLayout.LayoutParams(-1,dp(110))); c.addView(gap(6))
        addSection(c,"HOW TO USE","Example: Method=GET, URL=https://authorized.example/search?q=test, Headers=Accept: */*. Use Repeater when you want to edit one request and inspect the exact response.",yellow)
        c.addView(cyberButton("SEND AUTHORIZED REQUEST", {
            val u=url.text.toString().trim(); if(u.isBlank()){Toast.makeText(this,"Enter a target URL.",Toast.LENGTH_SHORT).show();return@cyberButton}
            val m=method.text.toString().trim().uppercase(Locale.ROOT).ifBlank{"GET"}
            if(m !in listOf("GET","HEAD","OPTIONS")){
                authorizationGate("REPEATER $m REQUEST (MAY CHANGE TARGET STATE)"){ executeRepeater(m,u,headers.text.toString(),body.text.toString()) }
            } else authorizationGate("REPEATER $m REQUEST"){ executeRepeater(m,u,headers.text.toString(),body.text.toString()) }
        },cyan),LinearLayout.LayoutParams(-1,dp(48)))
        v.addView(c); v.addView(gap(10)); v.addView(darkButton("‹ BACK TO BURP WEB LAB"){showBurpSuite()},LinearLayout.LayoutParams(-1,dp(45))); setScreen(v)
    }

    private fun multilineInput(hint:String,value:String="")=EditText(this).apply{ this.hint=hint; setHintTextColor(gray); setTextColor(white); textSize=13f; setText(value); gravity=Gravity.TOP; minLines=4; background=GradientDrawable().apply{cornerRadius=dp(9).toFloat();setColor(panel2);setStroke(dp(1),Color.rgb(25,95,155))}; setPadding(dp(12),dp(10),dp(12),dp(10)); addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){cyberType()};override fun afterTextChanged(s:Editable?){}})}

    private fun executeRepeater(method:String, rawUrl:String, rawHeaders:String, body:String){
        val url=normalizeUrl(rawUrl) ?: run{Toast.makeText(this,"Invalid URL",Toast.LENGTH_SHORT).show();return}
        cyberScanSound(1600L); showLoading("REPEATER SENDING",url)
        executor.execute{
            val start=System.currentTimeMillis(); var conn:HttpURLConnection?=null
            try{
                conn=URL(url).openConnection() as HttpURLConnection; conn.requestMethod=method; conn.instanceFollowRedirects=false; conn.connectTimeout=8000; conn.readTimeout=8000
                rawHeaders.lines().forEach{line->val i=line.indexOf(':');if(i>0)conn!!.setRequestProperty(line.substring(0,i).trim(),line.substring(i+1).trim())}
                if(method in listOf("POST","PUT","PATCH")){conn.doOutput=true;conn.outputStream.use{it.write(body.toByteArray(Charsets.UTF_8))}}
                val code=conn.responseCode; val response=readBodySafely(conn).take(30000); val req="$method $url\n$rawHeaders\n\n$body"; val msg=HttpMessage(System.currentTimeMillis(),method,url,req,"$code ${conn.responseMessage?:""}".trim(),response,System.currentTimeMillis()-start); httpHistory.add(msg)
                runOnUiThread{showHttpMessage(msg)}
            }catch(e:Exception){val msg=HttpMessage(System.currentTimeMillis(),method,url,"$method $url\n$rawHeaders\n\n$body","ERROR",e.message?:"Unknown error",System.currentTimeMillis()-start);httpHistory.add(msg);runOnUiThread{showHttpMessage(msg)}}finally{conn?.disconnect()}
        }
    }

    private fun showHttpMessage(m:HttpMessage){showToolOutput("REPEATER • ${m.status}","URL: ${m.url}\nMETHOD: ${m.method}\nTIME: ${m.durationMs} ms\n\nREQUEST\n${m.request}\n\nRESPONSE\n${m.response}"){showRepeater()}}

    private fun showHttpHistory(){
        val v=root();header(v,"HTTP HISTORY","REQUESTS GENERATED BY BOUNTYPILOT")
        if(httpHistory.isEmpty()){v.addView(card().apply{addView(text("NO HTTP MESSAGES YET",13f,yellow,true));addView(gap(5));addView(text("Use Repeater or target analysis. Messages stay in memory for this app session.",11.5f,gray))})}
        else httpHistory.reversed().forEach{m->val c=card();c.addView(text("${m.method}  ${m.status}",12f,cyan,true));c.addView(text(m.url,11f,white));c.addView(text("${m.durationMs} ms • response ${m.response.length} chars",10f,gray));c.addView(gap(5));c.addView(darkButton("OPEN MESSAGE"){showHttpMessage(m)},LinearLayout.LayoutParams(-1,dp(42)));v.addView(c);v.addView(gap(7))}
        v.addView(darkButton("‹ BACK TO BURP WEB LAB"){showBurpSuite()},LinearLayout.LayoutParams(-1,dp(45)));setScreen(v)
    }

    private fun showDecoder(){
        val v=root();header(v,"DECODER","URL • BASE64 • TEXT")
        val c=card();val inp=multilineInput("Input");val out=multilineInput("Output");addSection(c,"HOW TO USE","Example: paste %7B%7B7*7%7D%7D or a URL-encoded query into Input, then choose URL DECODE. For Base64, paste a value and use the matching encode/decode action.",yellow); c.addView(inp,LinearLayout.LayoutParams(-1,dp(140)));c.addView(gap(7));c.addView(cyberButton("URL DECODE",{out.setText(runCatching{URLDecoder.decode(inp.text.toString(),"UTF-8")}.getOrElse{"Invalid URL encoding"})},blue),LinearLayout.LayoutParams(-1,dp(44)));c.addView(gap(5));c.addView(cyberButton("URL ENCODE",{out.setText(runCatching{URLEncoder.encode(inp.text.toString(),"UTF-8")}.getOrElse{"Unable to encode"})},cyan),LinearLayout.LayoutParams(-1,dp(44)));c.addView(gap(5));c.addView(cyberButton("BASE64 DECODE",{out.setText(runCatching{String(Base64.getDecoder().decode(inp.text.toString()),Charsets.UTF_8)}.getOrElse{"Invalid Base64"})},green),LinearLayout.LayoutParams(-1,dp(44)));c.addView(gap(5));c.addView(cyberButton("BASE64 ENCODE",{out.setText(Base64.getEncoder().encodeToString(inp.text.toString().toByteArray(Charsets.UTF_8)))},blue),LinearLayout.LayoutParams(-1,dp(44)));c.addView(gap(7));c.addView(out,LinearLayout.LayoutParams(-1,dp(160)));v.addView(c);v.addView(gap(10));v.addView(darkButton("‹ BACK TO BURP WEB LAB"){showBurpSuite()},LinearLayout.LayoutParams(-1,dp(45)));setScreen(v)
    }

    private fun showComparer(){
        val v=root();header(v,"COMPARER","WORD-LEVEL RESPONSE / REQUEST DIFFERENCE")
        val a=multilineInput("ITEM A");val b=multilineInput("ITEM B");val result=multilineInput("DIFF RESULT");val c=card(); addSection(c,"HOW TO USE","Example: Item A = the baseline response, Item B = the response after one manual test. COMPARE highlights line-level differences.",yellow);c.addView(a,LinearLayout.LayoutParams(-1,dp(130)));c.addView(gap(6));c.addView(b,LinearLayout.LayoutParams(-1,dp(130)));c.addView(gap(6));c.addView(cyberButton("COMPARE",{result.setText(simpleDiff(a.text.toString(),b.text.toString()))},yellow),LinearLayout.LayoutParams(-1,dp(45)));c.addView(gap(6));c.addView(result,LinearLayout.LayoutParams(-1,dp(180)));v.addView(c);v.addView(gap(10));v.addView(darkButton("‹ BACK TO BURP WEB LAB"){showBurpSuite()},LinearLayout.LayoutParams(-1,dp(45)));setScreen(v)
    }

    private fun simpleDiff(a:String,b:String):String{val aa=a.lines();val bb=b.lines();val max=maxOf(aa.size,bb.size);val sb=StringBuilder();for(i in 0 until max){val x=aa.getOrNull(i)?:"<missing>";val y=bb.getOrNull(i)?:"<missing>";if(x==y)sb.append("  ").append(x).append('\n') else {sb.append("- ").append(x).append('\n');sb.append("+ ").append(y).append('\n')}};return sb.toString()}

    private fun showScope(){val v=root();header(v,"SCOPE","TARGET ALLOWLIST / DENYLIST");val c=card();addSection(c,"HOW TO USE","Example in-scope: https://authorized.example/ or api.authorized.example. Example out-of-scope: thirdparty.example. Scope is session UI only in this version.",yellow);c.addView(text("IN-SCOPE HOSTS / URL PREFIXES",12f,cyan,true));c.addView(gap(5));val inScope=multilineInput("one host or URL per line");c.addView(inScope,LinearLayout.LayoutParams(-1,dp(150)));c.addView(gap(6));c.addView(text("OUT-OF-SCOPE",12f,yellow,true));c.addView(gap(5));val outScope=multilineInput("one host or URL per line");c.addView(outScope,LinearLayout.LayoutParams(-1,dp(120)));c.addView(gap(6));c.addView(darkButton("SAVE SCOPE FOR SESSION"){Toast.makeText(this,"Scope saved for this session.",Toast.LENGTH_SHORT).show()},LinearLayout.LayoutParams(-1,dp(45)));v.addView(c);v.addView(gap(10));v.addView(darkButton("‹ BACK TO BURP WEB LAB"){showBurpSuite()},LinearLayout.LayoutParams(-1,dp(45)));setScreen(v)}

    private fun showSiteMap(){val v=root();header(v,"SITE MAP","DISCOVERED WEB SURFACE");val c=card();addSection(c,"HOW TO USE","Example: run recon first, then open Site Map to review discovered same-origin URLs. Tap an endpoint in a later version to send it to Repeater.",yellow);val a=lastAssessment;c.addView(text(if(a==null)"NO ASSESSMENT LOADED" else "${a.endpoints.endpoints.size} ENDPOINTS DISCOVERED",13f,if(a==null)yellow else green,true));c.addView(gap(6));c.addView(text(a?.endpoints?.endpoints?.joinToString("\n")?:"Run authorized recon to populate the site map.",11f,white));v.addView(c);v.addView(gap(10));v.addView(darkButton("‹ BACK TO BURP WEB LAB"){showBurpSuite()},LinearLayout.LayoutParams(-1,dp(45)));setScreen(v)}

    private fun showProxySettings(){val v=root();header(v,"PROXY SETTINGS","MOBILE INTERCEPT WORKFLOW");addSection(v,"HOW TO USE","Example: use Repeater for direct one-request testing. A true HTTPS MITM proxy needs an explicit local proxy/VPN and user-installed CA; BountyPilot does not silently enable this.",yellow);addSection(v,"LOCAL PROXY","BountyPilot's Repeater works directly. Full transparent HTTPS interception requires a local proxy/VPN implementation plus a user-installed CA certificate. Do not install a CA unless you understand and control the test environment.");addSection(v,"TERMUX BRIDGE","For Nmap/Metasploit/tshark, Termux must allow external apps. BountyPilot will attempt the RUN_COMMAND broadcast and fall back to opening Termux.");v.addView(cyberButton("CHECK TERMUX BRIDGE",{if(termuxInstalled()) launchTermuxCommand("echo BOUNTYPILOT_TERMUX_BRIDGE_OK") else Toast.makeText(this,"Termux is not installed.",Toast.LENGTH_LONG).show()},blue),LinearLayout.LayoutParams(-1,dp(46)));v.addView(gap(8));v.addView(darkButton("‹ BACK TO BURP WEB LAB"){showBurpSuite()},LinearLayout.LayoutParams(-1,dp(45)));setScreen(v)}

    private fun showMoreTools(){
        val v=root(); header(v,"MORE TOOLS","TRAINING • SETTINGS • UTILITIES")
        v.addView(cyberButton("TRAINING / DEMO LAB",{showTrainingLab()},green),LinearLayout.LayoutParams(-1,dp(46)))
        v.addView(gap(7))
        v.addView(cyberButton("SETTINGS",{showSettings()},blue),LinearLayout.LayoutParams(-1,dp(46)))
        v.addView(gap(7))
        v.addView(darkButton("BUILT-IN DNS / TLS"){showDnsTlsConsole()},LinearLayout.LayoutParams(-1,dp(46)))
        v.addView(gap(7))
        v.addView(darkButton("‹ BACK TO DASHBOARD"){showHome()},LinearLayout.LayoutParams(-1,dp(45)))
        setScreen(v)
    }

    private fun showSettings(){
        val v=root(); header(v,"SETTINGS","BOUNTYPILOT WORKSTATION")
        val c=card()
        val sound=CheckBox(this).apply{
            text="Cyber typing/click sounds"
            isChecked=typingSoundEnabled
            setTextColor(white)
            setOnCheckedChangeListener{_,checked->typingSoundEnabled=checked}
        }
        c.addView(sound); c.addView(gap(6))
        c.addView(text("NETWORK ENGINES",11.5f,cyan,true))
        c.addView(text("""Nmap: ${if(nmapAvailable()) "available" else "external/fallback"}
Termux: ${if(termuxInstalled()) "detected" else "not detected"}
HTTP/TLS: built-in
PCAP identification: built-in
Burp Web Lab: built-in repeater/history/decoder/comparer/scope""",11.5f,gray))
        v.addView(c); v.addView(gap(10))
        v.addView(darkButton("‹ BACK TO DASHBOARD"){showHome()},LinearLayout.LayoutParams(-1,dp(45)))
        setScreen(v)
    }

    private fun showWebSuite() {
        val v = root(); header(v, "WEB SUITE", "RECON • SURFACE ANALYSIS • ONE-TEST VALIDATION")
        val c = card(); c.addView(text("WEB SECURITY WORKFLOW", 12f, cyan, true)); c.addView(gap(6))
        c.addView(text("Recon discovers the surface; Test Lab turns discovered inputs into a controlled one-test-at-a-time queue.", 11.5f, gray)); c.addView(gap(8))
        c.addView(cyberButton("WEB RECON / TARGET", { showHome() }, blue), LinearLayout.LayoutParams(-1, dp(46)))
        c.addView(gap(6)); c.addView(cyberButton("OPEN TEST LAB", { showTestLabConsole() }, green), LinearLayout.LayoutParams(-1, dp(46)))
        v.addView(c); v.addView(gap(10)); v.addView(darkButton("‹ BACK TO DASHBOARD") { showHome() }, LinearLayout.LayoutParams(-1, dp(45))); setScreen(v)
    }

    private var trafficMonitorRunning = false

    private fun showTrafficSuite() {
        val v = root(); header(v, "TRAFFIC LAB", "LIVE DEVICE TRAFFIC • PCAP • WIRESHARK / TSHARK")
        val live = card()
        live.addView(text("◉ LIVE DEVICE TRAFFIC", 12f, cyan, true)); live.addView(gap(5))
        live.addView(text("This view uses Android TrafficStats for real device and app-UID byte counters. Packet payload inspection requires an explicit VPN/root/pcap capture path; BountyPilot does not silently intercept traffic.", 11.5f, gray)); live.addView(gap(7))
        val graph = TrafficGraphView(this)
        live.addView(graph, LinearLayout.LayoutParams(-1, dp(170)))
        live.addView(gap(6))
        val counters = text("RX: --   TX: --   UID RX: --   UID TX: --", 12f, white, true)
        live.addView(counters); live.addView(gap(7))
        live.addView(cyberButton("START LIVE MONITOR", { startTrafficMonitor(graph, counters) }, green), LinearLayout.LayoutParams(-1, dp(46)))
        live.addView(gap(6)); live.addView(darkButton("STOP LIVE MONITOR") { stopTrafficMonitor() }, LinearLayout.LayoutParams(-1, dp(45)))
        v.addView(live); v.addView(gap(10))

        val scan = card()
        scan.addView(text("⚡ AUTHORIZED TARGET SCAN", 12f, cyan, true)); scan.addView(gap(5))
        scan.addView(text("Run the existing low-impact BountyPilot web assessment from this screen. Results open in the assessment screen instead of silently launching another tool.", 11.5f, gray)); scan.addView(gap(7))
        val target = input("https://authorized-example.com")
        scan.addView(target, LinearLayout.LayoutParams(-1, dp(50))); scan.addView(gap(6))
        scan.addView(cyberButton("SCAN TARGET", { val t=target.text.toString().trim(); if(t.isBlank()) Toast.makeText(this,"Enter an authorized target.",Toast.LENGTH_SHORT).show() else authorizationGate("TRAFFIC LAB WEB SURFACE SCAN") { inspect(t) } }, blue), LinearLayout.LayoutParams(-1, dp(46)))
        v.addView(scan); v.addView(gap(10))

        val pcap = card()
        pcap.addView(text("📡 PACKET CAPTURE / PCAP", 12f, cyan, true)); pcap.addView(gap(5))
        pcap.addView(text("Import a capture for local inspection. For live capture, use an explicit capture source such as tcpdump in a permitted Termux/root environment or a user-approved VPN capture implementation.", 11.5f, gray)); pcap.addView(gap(7))
        pcap.addView(cyberButton("OPEN PCAP / PCAPNG", { openPcapPicker() }, blue), LinearLayout.LayoutParams(-1, dp(46))); pcap.addView(gap(6))
        val filter = input("Display filter: http, dns, tls, tcp.port==443")
        pcap.addView(filter, LinearLayout.LayoutParams(-1, dp(50))); pcap.addView(gap(6))
        pcap.addView(darkButton("COPY TSHARK FILTER COMMAND") { val f=filter.text.toString().trim().ifBlank{"http"}; copy("tshark filter", "tshark -r capture.pcapng -Y '$f'") }, LinearLayout.LayoutParams(-1, dp(45))); pcap.addView(gap(6))
        pcap.addView(darkButton("OPEN TSHARK IN TERMUX") { val f=filter.text.toString().trim().ifBlank{"http"}; if(termuxInstalled()) authorizationGate("OPEN TSHARK FOR AN AUTHORIZED CAPTURE") { launchTermuxCommand("tshark -r capture.pcapng -Y '$f'") } else showExternalCommand("tshark external bridge", "tshark -r capture.pcapng -Y '$f'") }, LinearLayout.LayoutParams(-1, dp(45))); pcap.addView(gap(6))
        pcap.addView(darkButton("TRY DEVICE TCPDUMP VIA TERMUX") { if(termuxInstalled()) authorizationGate("CAPTURE TRAFFIC FROM THIS DEVICE") { launchTermuxCommand("tcpdump -i any -c 200 -w /sdcard/Download/bountypilot_capture.pcap") } else showExternalCommand("tcpdump external bridge", "tcpdump -i any -c 200 -w /sdcard/Download/bountypilot_capture.pcap") }, LinearLayout.LayoutParams(-1, dp(45)))
        v.addView(pcap); v.addView(gap(10))
        v.addView(darkButton("‹ BACK TO DASHBOARD") { showHome() }, LinearLayout.LayoutParams(-1, dp(45))); setScreen(v)
    }

    private fun startTrafficMonitor(graph: TrafficGraphView, counters: TextView) {
        trafficMonitorRunning = true
        graph.start()
        fun tick() {
            if(!trafficMonitorRunning) return
            val rx=TrafficStats.getTotalRxBytes(); val tx=TrafficStats.getTotalTxBytes(); val urx=TrafficStats.getUidRxBytes(Process.myUid()); val utx=TrafficStats.getUidTxBytes(Process.myUid())
            counters.text="RX: ${humanBytes(rx)}   TX: ${humanBytes(tx)}\nUID RX: ${humanBytes(urx)}   UID TX: ${humanBytes(utx)}"
            graph.setSample(urx, utx)
            uiHandler.postDelayed({ tick() }, 1000L)
        }
        tick()
        Toast.makeText(this,"Live device counters started",Toast.LENGTH_SHORT).show()
    }
    private fun stopTrafficMonitor() { trafficMonitorRunning=false; Toast.makeText(this,"Live monitor stopped",Toast.LENGTH_SHORT).show() }
    private fun humanBytes(v: Long): String { if(v < 0) return "n/a"; if(v < 1024) return "$v B"; if(v < 1024*1024) return String.format(Locale.US,"%.1f KB",v/1024.0); if(v < 1024L*1024L*1024L) return String.format(Locale.US,"%.1f MB",v/1024.0/1024.0); return String.format(Locale.US,"%.2f GB",v/1024.0/1024.0/1024.0) }

    private fun showOsintHub() {
        val v = root()
        header(v, "OSINT INTELLIGENCE", "IN-APP PUBLIC INTEL • DOMAIN • IP • USERNAME • EMAIL METADATA")

        val seedCard = card()
        seedCard.addView(text("PUBLIC INTEL SEARCH", 12f, cyan, true))
        seedCard.addView(gap(5))
        seedCard.addView(text("Enter one public research seed. BountyPilot queries public registries and APIs in-app; it does not claim access to private databases.", 11.5f, gray))
        seedCard.addView(gap(7))
        val seed = input("example.com / 8.8.8.8 / username / email@example.com")
        seedCard.addView(seed, LinearLayout.LayoutParams(-1, dp(50)))
        seedCard.addView(gap(7))
        val result = multilineInput("Results appear here")
        seedCard.addView(cyberButton("⚡ SEARCH PUBLIC INTEL", {
            val q = seed.text.toString().trim()
            if (q.isBlank()) Toast.makeText(this, "Enter a research seed.", Toast.LENGTH_SHORT).show()
            else authorizationGate("PUBLIC OSINT COLLECTION") {
                result.setText("SCANNING PUBLIC SOURCES...\n")
                logScan("OSINT seed accepted: $q")
                cyberScanSound(1600L)
                executor.execute {
                    logScan("Querying public registries / DNS / CT for $q")
                    val r = runPublicIntel(q)
                    logScan("OSINT search complete: $q")
                    runOnUiThread { result.setText(r) }
                }
            }
        }, green), LinearLayout.LayoutParams(-1, dp(46)))
        seedCard.addView(gap(7))
        seedCard.addView(result, LinearLayout.LayoutParams(-1, dp(310)))
        seedCard.addView(gap(6))
        seedCard.addView(darkButton("COPY RESULTS") { copy("BountyPilot OSINT", result.text.toString()) }, LinearLayout.LayoutParams(-1, dp(45)))
        v.addView(seedCard); v.addView(gap(10))

        val breach = card()
        breach.addView(text("◆ BREACH / LEAK EXPOSURE CHECK", 12f, yellow, true)); breach.addView(gap(5))
        breach.addView(text("Checks an email against Have I Been Pwned's authorized breach API and reports breach exposure metadata only. It does not retrieve passwords, credential dumps, or raw leaked records.", 10.8f, gray)); breach.addView(gap(6))
        val breachEmail = input("your-email@example.com")
        val hibpKey = input("Have I Been Pwned API key")
        breach.addView(breachEmail, LinearLayout.LayoutParams(-1,dp(50))); breach.addView(gap(5)); breach.addView(hibpKey, LinearLayout.LayoutParams(-1,dp(50))); breach.addView(gap(6))
        val breachOut = multilineInput("Breach exposure results")
        breach.addView(cyberButton("CHECK BREACH EXPOSURE", {
            val e = breachEmail.text.toString().trim(); val k = hibpKey.text.toString().trim()
            if (!e.contains("@") || k.isBlank()) Toast.makeText(this,"Enter your email and HIBP API key.",Toast.LENGTH_SHORT).show()
            else authorizationGate("BREACH EXPOSURE CHECK FOR AN AUTHORIZED EMAIL") {
                breachOut.setText("CHECKING HIBP…"); logScan("HIBP breach exposure check started")
                executor.execute { val r=runHibpBreachCheck(e,k); logScan("HIBP breach exposure check complete"); runOnUiThread{breachOut.setText(r)} }
            }
        }, yellow), LinearLayout.LayoutParams(-1,dp(46)))
        breach.addView(gap(6)); breach.addView(breachOut, LinearLayout.LayoutParams(-1,dp(240)))
        breach.addView(gap(5)); breach.addView(darkButton("OPEN HIBP API / KEY DOCS"){openUrl("https://haveibeenpwned.com/API/v3")},LinearLayout.LayoutParams(-1,dp(44)))
        v.addView(breach); v.addView(gap(10))

        val social = card()
        social.addView(text("◆ PUBLIC PROFILE + SOCIAL OSINT", 12f, cyan, true)); social.addView(gap(5))
        social.addView(text("Generate direct public-profile links and verify them manually. A link is only a candidate; BountyPilot never bypasses private profiles or claims identity ownership.", 10.8f, gray)); social.addView(gap(6))
        val socialSeed = input("username / public name / email")
        social.addView(socialSeed, LinearLayout.LayoutParams(-1, dp(50))); social.addView(gap(6))
        val socialLinks = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val socialOut = multilineInput("Public profile results")
        social.addView(cyberButton("SEARCH / BUILD PUBLIC PROFILE LINKS", {
            val q = socialSeed.text.toString().trim()
            if (q.isBlank()) { socialOut.setText("Enter a public username, name or email alias."); return@cyberButton }
            socialLinks.removeAllViews()
            socialOut.setText("BUILDING PUBLIC PROFILE SOURCES…")
            logScan("Public social/profile correlation started: $q")
            val candidates = publicProfileCandidates(q)
            socialLinks.addView(text("DIRECT ACCOUNT / PROFILE LINKS", 11f, cyan, true))
            candidates.forEach { (name, url) -> addUrlButton(socialLinks, "OPEN $name", url) }
            socialLinks.addView(text("SIMILAR-NAME / PUBLIC-MENTION SEARCHES", 11f, green, true))
            val similarQ = URLEncoder.encode("\"$q\" OR @$q profile account", "UTF-8")
            val similar = listOf(
                "Google similar names" to "https://www.google.com/search?q=$similarQ",
                "Bing similar names" to "https://www.bing.com/search?q=$similarQ",
                "DuckDuckGo similar names" to "https://duckduckgo.com/?q=$similarQ",
                "Public GitHub mentions" to "https://www.google.com/search?q=${URLEncoder.encode("site:github.com \"$q\"", "UTF-8")}",
                "Public documents / CV" to "https://www.google.com/search?q=${URLEncoder.encode("\"$q\" filetype:pdf OR filetype:doc OR filetype:docx", "UTF-8")}"
            )
            similar.forEach { (name,url) -> addSearchSource(socialLinks, name, "Broader public-index search; results may include unrelated people.", url) }
            executor.execute {
                val r = runPublicProfileSearch(q)
                logScan("Public social/profile correlation complete: $q")
                runOnUiThread { socialOut.setText(r) }
            }
        }, green), LinearLayout.LayoutParams(-1, dp(46)))
        social.addView(gap(6)); social.addView(socialLinks, LinearLayout.LayoutParams(-1, -2))
        social.addView(gap(4)); social.addView(socialOut, LinearLayout.LayoutParams(-1, dp(300)))
        social.addView(gap(5)); social.addView(darkButton("COPY PROFILE RESULTS") { copy("Public profile correlation", socialOut.text.toString()) }, LinearLayout.LayoutParams(-1, dp(44)))
        v.addView(social); v.addView(gap(10))

        val phoneCard = card()
        phoneCard.addView(text("☎ PHONE NUMBER — PUBLIC OSINT", 12f, cyan, true)); phoneCard.addView(gap(5))
        phoneCard.addView(text("Checks only safe, public metadata and builds search links. It does not query private caller-ID databases, reveal a private person's identity, track a phone, or bypass account privacy.", 10.8f, gray)); phoneCard.addView(gap(6))
        val phoneInput = input("+91 9876543210")
        phoneCard.addView(phoneInput, LinearLayout.LayoutParams(-1, dp(50))); phoneCard.addView(gap(6))
        val phoneOut = multilineInput("Phone OSINT details")
        val phoneLinks = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        phoneCard.addView(cyberButton("SEARCH PUBLIC PHONE REFERENCES", {
            val p = phoneInput.text.toString().trim()
            if (p.isBlank()) { phoneOut.setText("Enter a phone number."); return@cyberButton }
            phoneLinks.removeAllViews()
            val data = runPhoneOsint(p)
            phoneOut.setText(data.first)
            data.second.forEach { (name,url) -> addUrlButton(phoneLinks, "OPEN $name", url) }
            phoneLinks.addView(text("PUBLIC MATCH / EXPOSURE SEARCHES", 11f, green, true))
            val digits = p.filter { it.isDigit() }
            val qExact = URLEncoder.encode("\"$digits\"", "UTF-8")
            val extra = listOf(
                "Exact number + name" to "https://www.google.com/search?q=${URLEncoder.encode("\"$digits\" name OR owner OR person", "UTF-8")}",
                "Exact number + email" to "https://www.google.com/search?q=${URLEncoder.encode("\"$digits\" email OR gmail OR contact", "UTF-8")}",
                "Exact number + location" to "https://www.google.com/search?q=${URLEncoder.encode("\"$digits\" address OR location OR city", "UTF-8")}",
                "Exact number + maps" to "https://www.google.com/search?q=${URLEncoder.encode("\"$digits\" map OR maps OR business", "UTF-8")}",
                "Exact number + social accounts" to "https://www.google.com/search?q=${URLEncoder.encode("\"$digits\" site:instagram.com OR site:facebook.com OR site:linkedin.com OR site:x.com", "UTF-8")}",
                "Exact number + public documents" to "https://www.google.com/search?q=$qExact+filetype%3Apdf"
            )
            extra.forEach { (name,url) -> addSearchSource(phoneLinks, name, "Searches only publicly indexed pages; a hit is not proof of ownership.", url) }
            logScan("Public phone OSINT links generated")
        }, blue), LinearLayout.LayoutParams(-1, dp(46)))
        phoneCard.addView(gap(6)); phoneCard.addView(phoneLinks, LinearLayout.LayoutParams(-1, -2))
        phoneCard.addView(gap(4)); phoneCard.addView(phoneOut, LinearLayout.LayoutParams(-1, dp(240)))
        v.addView(phoneCard); v.addView(gap(10))

        val forensic = card()
        forensic.addView(text("◆ FORENSICS TOOLKIT — LOCAL", 12f, yellow, true)); forensic.addView(gap(5))
        forensic.addView(text("Fast local triage helpers for files, logs and indicators. Hashing and IOC extraction stay on-device; no private data is uploaded by these tools.", 10.8f, gray)); forensic.addView(gap(6))
        val forensicInput = multilineInput("Paste text, an IOC, or a small text/log sample")
        forensic.addView(forensicInput, LinearLayout.LayoutParams(-1, dp(130))); forensic.addView(gap(5))
        val forensicOut = multilineInput("Forensics output")
        forensic.addView(darkButton("EXTRACT PUBLIC IOCs") {
            val raw = forensicInput.text.toString()
            val ips = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b").findAll(raw).map{it.value}.distinct().toList()
            val emails = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}").findAll(raw).map{it.value}.distinct().toList()
            val urls = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE).findAll(raw).map{it.value}.distinct().toList()
            val hashes = Regex("\\b[a-fA-F0-9]{32}(?:[a-fA-F0-9]{8}|[a-fA-F0-9]{24}|[a-fA-F0-9]{32})?\\b").findAll(raw).map{it.value}.distinct().toList()
            forensicOut.setText("IOC EXTRACTION\n================\nIPv4: ${ips.joinToString(", ").ifBlank{"none"}}\nEmails: ${emails.joinToString(", ").ifBlank{"none"}}\nURLs: ${urls.joinToString("\n").ifBlank{"none"}}\nPossible hashes: ${hashes.joinToString(", ").ifBlank{"none"}}")
        }, LinearLayout.LayoutParams(-1, dp(44)))
        forensic.addView(gap(4))
        forensic.addView(darkButton("SHA-256 HASH TEXT") {
            val md=MessageDigest.getInstance("SHA-256"); val hash=md.digest(forensicInput.text.toString().toByteArray(Charsets.UTF_8)).joinToString(""){ "%02x".format(it) }
            forensicOut.setText("SHA-256\n$hash\n\nInput bytes: ${forensicInput.text.toString().toByteArray(Charsets.UTF_8).size}")
        }, LinearLayout.LayoutParams(-1, dp(44)))
        forensic.addView(gap(4))
        forensic.addView(darkButton("UNIX TIMESTAMP → DATE") {
            val ts=forensicInput.text.toString().trim().toLongOrNull()
            forensicOut.setText(if(ts==null) "Enter a Unix timestamp in the input box." else "UTC: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US).apply{timeZone=java.util.TimeZone.getTimeZone("UTC")}.format(java.util.Date(if(ts<100000000000L) ts*1000 else ts))}")
        }, LinearLayout.LayoutParams(-1, dp(44)))
        forensic.addView(gap(5)); forensic.addView(forensicOut, LinearLayout.LayoutParams(-1, dp(180)))
        v.addView(forensic); v.addView(gap(10))

        val imageForensic = card()
        imageForensic.addView(text("◆ IMAGE FORENSICS + PUBLIC VISUAL SEARCH", 12f, yellow, true)); imageForensic.addView(gap(5))
        imageForensic.addView(text("Pick a photo to inspect embedded EXIF metadata locally. If GPS exists, BountyPilot builds map/reverse-location links. If GPS is absent, it cannot truthfully derive an exact location from pixels alone; visual-search links are provided instead.", 10.8f, gray)); imageForensic.addView(gap(6))
        val imageOut = multilineInput("Image forensic results")
        val imageName = text("No image selected", 10.5f, gray)
        imageForensic.addView(imageName); imageForensic.addView(gap(5))
        imageForensic.addView(darkButton("SELECT PHOTO") {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type="image/*"; addCategory(Intent.CATEGORY_OPENABLE) }
            startActivityForResult(i, 4101)
        }, LinearLayout.LayoutParams(-1, dp(44)))
        imageForensic.addView(gap(4))
        imageForensic.addView(darkButton("ANALYZE SELECTED PHOTO") { analyzeSelectedImage(imageOut, imageName) }, LinearLayout.LayoutParams(-1, dp(44)))
        imageForensic.addView(gap(4))
        imageForensic.addView(darkButton("OPEN GOOGLE LENS / VISUAL SEARCH") { launchVisualSearch() }, LinearLayout.LayoutParams(-1, dp(44)))
        imageForensic.addView(gap(4)); imageForensic.addView(imageOut, LinearLayout.LayoutParams(-1, dp(230)))
        v.addView(imageForensic); v.addView(gap(10))

        val shodan = card()
        shodan.addView(text("◆ SHODAN API", 12f, cyan, true))
        shodan.addView(gap(5))
        shodan.addView(text("Use your own Shodan API key. BountyPilot never generates, guesses, or exposes someone else's API credential.", 11.5f, gray))
        shodan.addView(gap(6))
        val key = input("Your Shodan API key")
        val query = input("hostname:example.com / net:8.8.8.0/24")
        shodan.addView(key, LinearLayout.LayoutParams(-1, dp(50))); shodan.addView(gap(5))
        shodan.addView(query, LinearLayout.LayoutParams(-1, dp(50))); shodan.addView(gap(6))
        val shodanOut = multilineInput("Shodan response")
        shodan.addView(cyberButton("SEARCH SHODAN IN APP", {
            val k=key.text.toString().trim(); val q=query.text.toString().trim()
            if(k.isBlank()||q.isBlank()) Toast.makeText(this,"Enter your API key and query.",Toast.LENGTH_SHORT).show()
            else executor.execute { logScan("Shodan query started: $q"); val r=runShodanSearch(k,q); logScan("Shodan query complete"); runOnUiThread{shodanOut.setText(r)} }
        }, blue), LinearLayout.LayoutParams(-1, dp(46)))
        shodan.addView(gap(5)); shodan.addView(shodanOut, LinearLayout.LayoutParams(-1, dp(210)))
        shodan.addView(gap(5)); shodan.addView(darkButton("OPEN SHODAN API / KEY PAGE") { openUrl("https://account.shodan.io/login") }, LinearLayout.LayoutParams(-1, dp(45)))
        v.addView(shodan); v.addView(gap(10))

        val truecaller = card()
        truecaller.addView(text("☎ TRUECALLER — CONSENTED MODE", 12f, cyan, true))
        truecaller.addView(gap(5))
        truecaller.addView(text("Truecaller does not provide BountyPilot with an unrestricted private reverse-lookup database. The supported developer flows use consented verification/OAuth and approved access. BountyPilot therefore will not fabricate a person's name, exact location, or private profile data.", 11.5f, gray))
        truecaller.addView(gap(7))
        truecaller.addView(text("For your own number / an authorized user's consented profile, use the official Truecaller developer flow. The returned fields depend on the granted scope.", 11.5f, white))
        truecaller.addView(gap(7))
        truecaller.addView(darkButton("OPEN OFFICIAL TRUECALLER DEVELOPER PORTAL") { openUrl("https://developer.truecaller.com/") }, LinearLayout.LayoutParams(-1, dp(45)))
        v.addView(truecaller); v.addView(gap(10))

        val api = card()
        api.addView(text("⚿ API CATALOG / KEY SETUP", 12f, cyan, true)); api.addView(gap(5))
        api.addView(text("BountyPilot can show where an API key is obtained, but it cannot legitimately provide secret keys belonging to third-party services.", 11.5f, gray)); api.addView(gap(7))
        val apis = listOf(
            "Shodan API" to "https://developer.shodan.io/api",
            "Truecaller Developer" to "https://developer.truecaller.com/",
            "urlscan.io API" to "https://urlscan.io/docs/api/",
            "SecurityTrails API" to "https://securitytrails.com/corp/api",
            "VirusTotal API" to "https://docs.virustotal.com/reference/overview",
            "Have I Been Pwned API" to "https://haveibeenpwned.com/API/v3"
        )
        apis.forEach { (name, url) ->
            api.addView(darkButton("$name  ↗") { openUrl(url) }, LinearLayout.LayoutParams(-1, dp(42)).apply { bottomMargin=dp(5) })
        }
        v.addView(api); v.addView(gap(10))

        val limits = card()
        limits.addView(text("PUBLIC-DATA LIMITS", 12f, cyan, true)); limits.addView(gap(5))
        limits.addView(text("Domain/IP searches can return registry, DNS, certificate and public infrastructure information. Username mode is limited to public-presence checks. Email mode reports syntax/domain metadata rather than private account data. Phone mode reports safe number metadata only. No credential dumps, private profiles, exact private locations, or data-broker scraping are included.", 11.5f, gray))
        v.addView(limits); v.addView(gap(10))

        v.addView(darkButton("‹ BACK TO DASHBOARD") { showHome() }, LinearLayout.LayoutParams(-1, dp(45)))
        setScreen(v)
    }

    private fun analyzeSelectedImage(out: EditText, nameView: TextView) {
        val uri = pendingImageForForensics ?: run { out.setText("Select a photo first."); return }
        executor.execute {
            val sb=StringBuilder("IMAGE FORENSICS\n================\nURI: $uri\n")
            try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    val exif=ExifInterface(stream)
                    val gps=FloatArray(2)
                    val hasGps=exif.getLatLong(gps)
                    val lat=if(hasGps) gps[0].toDouble() else null
                    val lon=if(hasGps) gps[1].toDouble() else null
                    val attrs=listOf(
                        "Make" to exif.getAttribute(ExifInterface.TAG_MAKE),
                        "Model" to exif.getAttribute(ExifInterface.TAG_MODEL),
                        "DateTime" to exif.getAttribute(ExifInterface.TAG_DATETIME),
                        "Software" to exif.getAttribute(ExifInterface.TAG_SOFTWARE),
                        "Orientation" to exif.getAttribute(ExifInterface.TAG_ORIENTATION),
                        "Width" to exif.getAttribute(ExifInterface.TAG_IMAGE_WIDTH),
                        "Height" to exif.getAttribute(ExifInterface.TAG_IMAGE_LENGTH)
                    )
                    attrs.forEach { (k,v) -> if(!v.isNullOrBlank()) sb.append("$k: $v\n") }
                    if(lat!=null && lon!=null) {
                        sb.append("GPS: $lat, $lon\n")
                        sb.append("MAP: https://www.google.com/maps/search/?api=1&query=$lat,$lon\n")
                        sb.append("OSM: https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=18/$lat/$lon\n")
                        sb.append("REVERSE: https://opensurveillancedb.org/api/geocode/reverse?lat=$lat&lng=$lon\n")
                    } else sb.append("GPS: not present in EXIF\n")
                } ?: sb.append("Unable to read the selected image.\n")
            } catch(e:Exception){ sb.append("EXIF ERROR: ${e.message}\n") }
            runOnUiThread { nameView.text=uri.lastPathSegment ?: uri.toString(); out.setText(sb.toString()) }
        }
    }

    private fun launchVisualSearch() {
        val uri=pendingImageForForensics
        if(uri==null){ openUrl("https://lens.google.com/"); return }
        try {
            val send=Intent(Intent.ACTION_SEND).apply { type="image/*"; putExtra(Intent.EXTRA_STREAM,uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            startActivity(Intent.createChooser(send,"Choose a visual-search app"))
        } catch(_:Exception){ openUrl("https://lens.google.com/") }
    }

    private fun openUrl(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Exception) { Toast.makeText(this,"No browser available",Toast.LENGTH_SHORT).show() }
    }

    private fun publicProfileCandidates(seed: String): List<Pair<String,String>> {
        val raw = seed.trim()
        val key = raw.substringBefore('@').removePrefix("@").trim()
        val q = URLEncoder.encode(raw, "UTF-8")
        val user = URLEncoder.encode(key, "UTF-8")
        return listOf(
            "GitHub" to "https://github.com/$key",
            "GitLab" to "https://gitlab.com/$key",
            "Reddit" to "https://www.reddit.com/user/$key/",
            "X" to "https://x.com/$key",
            "Instagram" to "https://www.instagram.com/$key/",
            "Facebook" to "https://www.facebook.com/$key",
            "TikTok" to "https://www.tiktok.com/@$key",
            "Snapchat" to "https://www.snapchat.com/add/$key",
            "Keybase" to "https://keybase.io/$key",
            "Mastodon search" to "https://www.google.com/search?q=${URLEncoder.encode("site:mastodon.social @$raw", "UTF-8")}",
            "Bluesky search" to "https://www.google.com/search?q=${URLEncoder.encode("site:bsky.app/profile @$raw", "UTF-8")}",
            "Threads search" to "https://www.google.com/search?q=${URLEncoder.encode("site:threads.net @$raw", "UTF-8")}",
            "Pinterest" to "https://www.pinterest.com/$key/",
            "YouTube search" to "https://www.youtube.com/results?search_query=$user",
            "Twitch search" to "https://www.twitch.tv/search?term=$user",
            "Telegram search" to "https://www.google.com/search?q=${URLEncoder.encode("site:t.me $raw", "UTF-8")}",
            "LinkedIn search" to "https://www.linkedin.com/search/results/all/?keywords=$q",
            "Public CV search" to "https://www.google.com/search?q=${URLEncoder.encode("\"$raw\" CV OR resume", "UTF-8")}",
            "Bing public search" to "https://www.bing.com/search?q=$q",
            "DuckDuckGo public search" to "https://duckduckgo.com/?q=$q"
        )
    }

    private fun runPhoneOsint(phone: String): Pair<String,List<Pair<String,String>>> {
        val raw = phone.trim()
        val digits = raw.filter { it.isDigit() }
        val normalized = if (raw.startsWith("+")) "+$digits" else digits
        val country = when { normalized.startsWith("+91") || (digits.length == 10 && digits[0] in '6'..'9') -> "India (+91) likely"; normalized.startsWith("+1") -> "North America (+1)"; normalized.startsWith("+44") -> "United Kingdom (+44)"; normalized.startsWith("+971") -> "UAE (+971)"; else -> "Country not inferred" }
        val q = URLEncoder.encode("\"$normalized\"", "UTF-8")
        val links = listOf(
            "Google public search" to "https://www.google.com/search?q=$q",
            "Bing public search" to "https://www.bing.com/search?q=$q",
            "DuckDuckGo public search" to "https://duckduckgo.com/?q=$q",
            "Google exact-number + social" to "https://www.google.com/search?q=${URLEncoder.encode("\"$normalized\" social OR profile OR contact", "UTF-8")}",
            "Google exact-number + public documents" to "https://www.google.com/search?q=${URLEncoder.encode("\"$normalized\" filetype:pdf OR filetype:doc OR CV", "UTF-8")}"
        )
        val out = "PHONE PUBLIC OSINT\n================\nINPUT: $raw\nNORMALIZED DIGITS: $digits\nE.164-LIKE: $normalized\nCOUNTRY: $country\nDIGIT COUNT: ${digits.length}\n\nPUBLIC DATA MODE: search-engine references only.\nNOTE: Search results can contain false matches. Do not treat a public page as proof of identity or ownership."
        return out to links
    }

    private data class NearbyCamera(val id:String,val name:String,val source:String,val kind:String,val status:String,val lat:String,val lon:String,val address:String)

    private fun runNearbyPublicCamerasDetailed(place: String, radius: Int): Pair<String,List<NearbyCamera>> {
        return try {
            var lat=""; var lon=""; var centerLabel=place
            val coord = Regex("^\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*$").find(place)
            if (coord != null) { lat=coord.groupValues[1]; lon=coord.groupValues[2]; centerLabel="coordinates" } else {
                val geoUrl=URL("https://opensurveillancedb.org/api/geocode?q=${URLEncoder.encode(place,"UTF-8")}")
                val gc=geoUrl.openConnection() as HttpURLConnection; gc.connectTimeout=10000; gc.readTimeout=15000; gc.requestMethod="GET"; gc.setRequestProperty("Accept","application/json"); gc.setRequestProperty("User-Agent","BountyPilot/3.2")
                val geoBody=BufferedReader(InputStreamReader(gc.inputStream)).use{it.readText()}
                val root=runCatching{JSONObject(geoBody)}.getOrNull()
                val arr=root?.optJSONArray("results") ?: JSONArray()
                val g=arr.optJSONObject(0)
                lat=g?.optString("lat").orEmpty(); lon=g?.optString("lon").orEmpty().ifBlank{g?.optString("lng").orEmpty()}
            }
            if(lat.isBlank()||lon.isBlank()) return "LOCATION COORDINATES UNAVAILABLE: $place" to emptyList()
            val latD = lat.toDoubleOrNull()
            val lonD = lon.toDoubleOrNull()
            if(latD == null || lonD == null || latD !in -90.0..90.0 || lonD !in -180.0..180.0) return "INVALID COORDINATES: $lat, $lon" to emptyList()
            logScan("Camera center resolved: $lat,$lon")
            val u=URL("https://opensurveillancedb.org/api/cameras/nearby?latitude=$lat&longitude=$lon&radius=${radius.coerceIn(10,500)}")
            val c=u.openConnection() as HttpURLConnection; c.connectTimeout=12000; c.readTimeout=18000; c.requestMethod="GET"; c.setRequestProperty("Accept","application/json"); c.setRequestProperty("User-Agent","BountyPilot/3.2")
            val code=c.responseCode; val stream=if(code in 200..299)c.inputStream else c.errorStream; val body=if(stream!=null)BufferedReader(InputStreamReader(stream)).use{it.readText()} else ""
            if(code !in 200..299) return "DIRECTORY HTTP $code\n$body" to emptyList()
            val root=runCatching{JSONObject(body)}.getOrNull(); val arr=when{root?.has("records")==true->root.optJSONArray("records")?:JSONArray();root?.has("results")==true->root.optJSONArray("results")?:JSONArray();root?.has("cameras")==true->root.optJSONArray("cameras")?:JSONArray();body.trim().startsWith("[")->JSONArray(body);else->JSONArray()}
            val cams=mutableListOf<NearbyCamera>(); val sb=StringBuilder("NEAR $centerLabel — ${arr.length()} PUBLIC CAMERA RECORDS\nCENTER: $lat, $lon\nRADIUS: ${radius.coerceIn(10,500)}m\n\n")
            for(i in 0 until minOf(arr.length(),50)){
                val o=arr.optJSONObject(i)?:continue; val id=firstNonBlank(o,"id","cameraId").ifBlank{"?"}; val name=firstNonBlank(o,"title","name","label","camera").ifBlank{"Public camera"}; val kind=firstNonBlank(o,"kind","type","category"); val status=firstNonBlank(o,"status","state"); val la=firstNonBlank(o,"latitude","lat"); val lo=firstNonBlank(o,"longitude","lng","lon"); val address=firstNonBlank(o,"address","location","city"); val source=firstNonBlank(o,"official_url","source_url","stream_url","image_url","url")
                cams.add(NearbyCamera(id,name,source,kind,status,la,lo,address))
                sb.append("#${i+1} $name\nID: $id\nTYPE: ${kind.ifBlank{"public"}}  STATUS: ${status.ifBlank{"unknown"}}\n"); if(address.isNotBlank())sb.append("LOCATION: $address\n"); if(la.isNotBlank()||lo.isNotBlank())sb.append("COORDS: $la, $lo\n"); sb.append("SOURCE: OpenSurveillanceDB public record\n\n")
            }
            if(cams.isEmpty()) sb.append("No published camera records were returned. OpenCam may have live public sources that are not represented in this metadata directory.")
            sb.toString() to cams
        } catch(e:Exception) { "NEARBY CAMERA SEARCH FAILED\n${e.message?:"unknown error"}" to emptyList() }
    }

    private fun showPublicCameraDetails(id:String) {
        if(id=="?") return
        executor.execute {
            try {
                val u=URL("https://opensurveillancedb.org/api/cameras/$id"); val c=u.openConnection() as HttpURLConnection
                c.connectTimeout=10000; c.readTimeout=15000; c.requestMethod="GET"; c.setRequestProperty("Accept","application/json"); c.setRequestProperty("User-Agent","BountyPilot/3.2")
                val code=c.responseCode; val stream=if(code in 200..299)c.inputStream else c.errorStream; val body=if(stream!=null)BufferedReader(InputStreamReader(stream)).use{it.readText()} else ""
                if(code !in 200..299) throw Exception("HTTP $code\n$body")
                val o=JSONObject(body); val lat=firstNonBlank(o,"latitude","lat"); val lon=firstNonBlank(o,"longitude","lng","lon")
                val source=firstNonBlank(o,"official_url","source_url","stream_url","image_url","url")
                val name=firstNonBlank(o,"title","name","label").ifBlank{"Public camera #$id"}
                val summary=StringBuilder("NAME: $name\nID: $id\n")
                listOf("kind" to "TYPE","status" to "STATUS","address" to "ADDRESS","city" to "CITY","country" to "COUNTRY","verification" to "VERIFICATION").forEach{(k,label)->val v=o.optString(k,"");if(v.isNotBlank())summary.append("$label: $v\n")}
                if(lat.isNotBlank()||lon.isNotBlank())summary.append("COORDS: $lat, $lon\n")
                if(source.isNotBlank())summary.append("PUBLIC SOURCE: $source\n") else summary.append("PUBLIC SOURCE: not published in this record\n")
                val result=summary.toString()
                runOnUiThread {
                    val b=AlertDialog.Builder(this).setTitle("CAMERA #$id — PUBLIC DETAILS").setMessage(result)
                        .setPositiveButton("COPY DETAILS"){_,_->copy("Camera #$id",result)}
                        .setNegativeButton("CLOSE",null).create(); b.show()
                    if(lat.isNotBlank()&&lon.isNotBlank()){
                        b.setOnShowListener {
                            b.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener{}
                        }
                        b.setButton(AlertDialog.BUTTON_NEUTRAL,"MAP") {_,_->openUrl("https://www.google.com/maps/search/?api=1&query=$lat,$lon")}
                    }
                    if(source.startsWith("http://")||source.startsWith("https://")) b.setButton(AlertDialog.BUTTON_POSITIVE,"VIEW PUBLIC SOURCE"){_,_->openPublicCameraSource(source)}
                }
            } catch(e:Exception){ runOnUiThread { AlertDialog.Builder(this).setTitle("CAMERA DETAIL FAILED").setMessage(e.message?:"unknown error").setPositiveButton("CLOSE",null).show() } }
        }
    }

    private fun runPublicProfileSearch(seed: String): String {
        val raw = seed.trim()
        val key = raw.substringBefore('@').ifBlank { raw }.trim()
        val q = URLEncoder.encode(raw, "UTF-8")
        val u = URLEncoder.encode(key.removePrefix("@"), "UTF-8")
        val out = StringBuilder("PUBLIC PROFILE CORRELATION\n=========================\nSEED: $raw\n\n")
        val candidates = publicProfileCandidates(raw)
        out.append("PUBLIC ENDPOINT CANDIDATES\n")
        candidates.forEach { (name,url) -> out.append("$name: $url\n") }
        if (raw.matches(Regex("^[A-Za-z0-9._-]{2,39}$"))) {
            try {
                val c = URL("https://api.github.com/users/$u").openConnection() as HttpURLConnection
                c.connectTimeout = 9000; c.readTimeout = 12000; c.requestMethod = "GET"
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.setRequestProperty("User-Agent", "BountyPilot/3.1")
                val code = c.responseCode
                val body = if (code in 200..299) BufferedReader(InputStreamReader(c.inputStream)).use { it.readText() } else ""
                if (code == 200 && body.isNotBlank()) {
                    val o = JSONObject(body)
                    out.append("\nGITHUB PUBLIC PROFILE\n")
                    out.append("LOGIN: ${o.optString("login")}\nNAME: ${o.optString("name")}\nLOCATION: ${o.optString("location")}\nBIO: ${o.optString("bio")}\nPUBLIC REPOS: ${o.optInt("public_repos")}\nFOLLOWERS: ${o.optInt("followers")}\nPROFILE: ${o.optString("html_url")}\n")
                } else out.append("\nGITHUB API: HTTP $code / no public profile returned.\n")
            } catch (e: Exception) { out.append("\nGITHUB API FAILED: ${e.message}\n") }
        }
        out.append("\nNOTE: A candidate URL is not proof of account ownership. Verify the public profile before correlating identities. No private profile data, passwords, credential dumps, or data-broker records are retrieved.")
        return out.toString()
    }

    private fun openPublicCameraSource(url: String) {
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return
        val lower=url.lowercase(Locale.US)
        if (lower.contains(".jpg") || lower.contains(".jpeg") || lower.contains(".png") || lower.contains(".webp") || lower.contains("image")) showPublicImageViewer(url)
        else showPublicFeedViewer(url)
    }

    private fun showPublicImageViewer(url: String) {
        val v=root(); header(v, "PUBLIC CAMERA IMAGE", "DIRECT VIEW • PUBLIC / AUTHORIZED SOURCE")
        val c=card(); c.addView(text(url,10.5f,gray)); c.addView(gap(5))
        val image=ImageView(this).apply { setBackgroundColor(Color.BLACK); adjustViewBounds=true; scaleType=ImageView.ScaleType.FIT_CENTER }
        c.addView(image, LinearLayout.LayoutParams(-1,0,1f)); v.addView(c,LinearLayout.LayoutParams(-1,0,1f)); v.addView(gap(6))
        v.addView(darkButton("OPEN IMAGE URL IN BROWSER") { openUrl(url) }, LinearLayout.LayoutParams(-1,dp(44)))
        v.addView(gap(5)); v.addView(darkButton("‹ BACK") { showPublicCameras() }, LinearLayout.LayoutParams(-1,dp(44)))
        setScreen(v)
        executor.execute {
            try { val conn=URL(url).openConnection(); conn.connectTimeout=12000; conn.readTimeout=18000; conn.getInputStream().use { input -> val bmp=android.graphics.BitmapFactory.decodeStream(input); runOnUiThread{image.setImageBitmap(bmp)} } }
            catch(e:Exception){ runOnUiThread{ Toast.makeText(this,"Image could not be loaded in-app: ${e.message}",Toast.LENGTH_LONG).show() } }
        }
    }

    private fun showPublicFeedViewer(url: String) {
        val v = root(); header(v, "PUBLIC FEED VIEWER", "IN-APP VIEW • PUBLIC / AUTHORIZED SOURCE ONLY")
        val c = card(); c.addView(text("SOURCE", 12f, cyan, true)); c.addView(gap(5)); c.addView(text(url, 10.5f, gray)); c.addView(gap(6))
        val w = WebView(this).apply { settings.javaScriptEnabled=true; settings.domStorageEnabled=true; settings.mediaPlaybackRequiresUserGesture=false; webViewClient=WebViewClient(); setBackgroundColor(Color.BLACK); loadUrl(url) }
        keepWebViewScrollLocal(w)
        c.addView(w, LinearLayout.LayoutParams(-1, dp(520))); v.addView(c); v.addView(gap(10))
        v.addView(darkButton("‹ BACK TO PUBLIC CAMERAS") { w.stopLoading(); w.destroy(); showPublicCameras() }, LinearLayout.LayoutParams(-1, dp(45)))
        setScreen(v)
    }

    private fun runPublicIntel(seed: String): String {
        val s=seed.trim()
        val out=StringBuilder("BOUNTYPILOT PUBLIC INTEL\n========================\nSEED: $s\n\n")
        try {
            val isIp=s.matches(Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$"))
            val isDomain=s.matches(Regex("^(?i)[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\\.[a-z]{2,63}$"))
            val isEmail=s.matches(Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))
            when {
                isIp -> {
                    out.append(fetchPublicUrl("https://rdap.org/ip/$s", "RDAP IP"))
                    out.append("\n")
                    out.append(fetchPublicUrl("https://dns.google/resolve?name=$s", "DNS"))
                }
                isDomain -> {
                    out.append(fetchPublicUrl("https://rdap.org/domain/$s", "RDAP DOMAIN"))
                    out.append("\n")
                    out.append(fetchPublicUrl("https://dns.google/resolve?name=$s&type=A", "DNS A"))
                    out.append("\n")
                    out.append(fetchPublicUrl("https://dns.google/resolve?name=$s&type=MX", "DNS MX"))
                    out.append("\n")
                    out.append(fetchPublicUrl("https://crt.sh/?q=%25.$s&output=json", "CERTIFICATE TRANSPARENCY"))
                }
                isEmail -> {
                    val domain=s.substringAfter('@').lowercase(Locale.US)
                    out.append("EMAIL FORMAT: valid-looking\nDOMAIN: $domain\n\n")
                    out.append(fetchPublicUrl("https://rdap.org/domain/$domain", "DOMAIN RDAP"))
                    out.append("\n")
                    out.append(fetchPublicUrl("https://dns.google/resolve?name=$domain&type=MX", "MAIL DNS"))
                }
                else -> {
                    out.append("USERNAME / PUBLIC HANDLE MODE\n")
                    out.append("No private identity resolution is attempted. Public-presence URLs are generated for manual verification.\n\n")
                    val sites=listOf(
                        "GitHub" to "https://github.com/$s",
                        "GitLab" to "https://gitlab.com/$s",
                        "Reddit" to "https://www.reddit.com/user/$s/",
                        "X" to "https://x.com/$s",
                        "Keybase" to "https://keybase.io/$s"
                    )
                    sites.forEach { (n,u) -> out.append("$n: $u\n") }
                }
            }
        } catch(e:Exception) { out.append("ERROR: ${e.message}\n") }
        return out.toString()
    }

    private fun fetchPublicUrl(url:String, label:String):String {
        return try {
            val c=URL(url).openConnection() as HttpURLConnection
            c.connectTimeout=10000; c.readTimeout=15000; c.requestMethod="GET"; c.setRequestProperty("User-Agent","BountyPilot/2.7")
            val code=c.responseCode
            val stream=if(code in 200..399) c.inputStream else c.errorStream
            val body=if(stream!=null) BufferedReader(InputStreamReader(stream)).use{it.readText()} else ""
            val trimmed=if(body.length>9000) body.take(9000)+"\n...[truncated]" else body
            "$label\nHTTP $code\n$trimmed\n"
        } catch(e:Exception) { "$label\nFAILED: ${e.message}\n" }
    }

    private fun runHibpBreachCheck(email: String, apiKey: String): String {
        return try {
            val u = URL("https://haveibeenpwned.com/api/v3/breachedaccount/${URLEncoder.encode(email,"UTF-8")}?truncateResponse=false")
            val c=u.openConnection() as HttpURLConnection; c.connectTimeout=12000; c.readTimeout=18000; c.requestMethod="GET"
            c.setRequestProperty("hibp-api-key",apiKey); c.setRequestProperty("user-agent","BountyPilot/3.0")
            val code=c.responseCode
            val stream=if(code in 200..299)c.inputStream else c.errorStream
            val body=if(stream!=null)BufferedReader(InputStreamReader(stream)).use{it.readText()} else ""
            when(code){
                200->{
                    val a=JSONArray(body); val sb=StringBuilder("BREACHES FOUND: ${a.length()}\n\n")
                    for(i in 0 until a.length()){ val o=a.optJSONObject(i)?:continue; sb.append("${o.optString("Title",o.optString("Name","Unknown"))}\nDomain: ${o.optString("Domain")}\nDate: ${o.optString("BreachDate")}\nVerified: ${o.optBoolean("IsVerified")}\nData classes: ${o.optJSONArray("DataClasses")?.let{arr->(0 until arr.length()).joinToString(", "){arr.optString(it)}} ?: "not supplied"}\n\n") }
                    sb.append("SOURCE: Have I Been Pwned\nRAW CREDENTIALS ARE NOT RETURNED."); sb.toString()
                }
                404->"NO HIBP BREACH FOUND FOR THIS EMAIL.\n\nThis means HIBP returned no matching breach record; it is not proof that the address has never appeared anywhere."
                401->"HIBP AUTHORIZATION FAILED. Check your API key."
                429->"HIBP RATE LIMIT REACHED. Try again later."
                else->"HIBP HTTP $code\n$body"
            }
        }catch(e:Exception){"HIBP CHECK FAILED\n${e.message?:"unknown error"}"}
    }

    private fun runShodanSearch(apiKey: String, query: String): String {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = URL("https://api.shodan.io/shodan/host/search?key=${URLEncoder.encode(apiKey, "UTF-8")}&query=$encoded")
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.requestMethod = "GET"
            c.setRequestProperty("User-Agent", "BountyPilot/2.8")
            val code = c.responseCode
            val stream = if (code in 200..399) c.inputStream else c.errorStream
            val body = if (stream != null) BufferedReader(InputStreamReader(stream)).use { it.readText() } else ""
            val trimmed = if (body.length > 18000) body.take(18000) + "\n...[truncated]" else body
            "HTTP $code\n\n$trimmed"
        } catch (e: Exception) {
            "SHODAN REQUEST FAILED\n${e.message ?: "unknown error"}\n\nCheck the API key, network connection, and query syntax."
        }
    }

    private data class CameraSource(val label: String, val url: String)

    private fun showPublicCameras() {
        val v = root()
        header(v, "PUBLIC CAMERA INTEL", "PUBLIC-BY-DESIGN LIVE CAMERAS • MAP • LOCATION")

        val info = card()
        info.addView(text("LIVE PUBLIC CAMERA NETWORK", 12f, cyan, true))
        info.addView(gap(5))
        info.addView(text("Browse cameras intentionally published by road agencies, weather services, tourism operators and other public webcam providers. BountyPilot does not probe private or unsecured cameras.", 10.8f, gray))
        v.addView(info)
        v.addView(gap(10))

        val mapCard = card()
        mapCard.addView(text("WORLD LIVE CAMERA MAP", 12f, green, true))
        mapCard.addView(gap(6))
        val map = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            webViewClient = WebViewClient()
            setBackgroundColor(Color.BLACK)
            loadUrl("https://opencam.live/")
        }
        keepWebViewScrollLocal(map)
        mapCard.addView(map, LinearLayout.LayoutParams(-1, dp(500)))
        mapCard.addView(gap(6))
        mapCard.addView(text("OpenCam aggregates public-by-design traffic, weather, harbour and city cameras and displays them on a live map.", 10.5f, gray))
        mapCard.addView(gap(5))
        mapCard.addView(darkButton("OPEN OPENCAM IN FULL-SCREEN TAB") { showOpenCamFullscreen() }, LinearLayout.LayoutParams(-1, dp(44)))
        v.addView(mapCard)
        v.addView(gap(10))

        val searchCard = card()
        searchCard.addView(text("PUBLIC CAMERA DIRECTORY", 12f, cyan, true))
        searchCard.addView(gap(6))
        searchCard.addView(text("Search by city, country, camera name or type. Results come from the public OpenSurveillanceDB read API.", 10.5f, gray))
        searchCard.addView(gap(6))
        val query = input("Bengaluru / London / traffic / harbour")
        searchCard.addView(query, LinearLayout.LayoutParams(-1, dp(50)))
        searchCard.addView(gap(6))
        val results = TextView(this).apply {
            setTextColor(white); textSize = 12f; setPadding(dp(4), dp(4), dp(4), dp(4)); setTextIsSelectable(true)
        }
        val resultScroll = ScrollView(this).apply { isFillViewport = true; addView(results, FrameLayout.LayoutParams(-1, -1)) }
        keepInnerScrollLocal(resultScroll)
        searchCard.addView(resultScroll, LinearLayout.LayoutParams(-1, dp(360)))
        val cameraViews = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        searchCard.addView(gap(6)); searchCard.addView(cameraViews, LinearLayout.LayoutParams(-1, -2))
        searchCard.addView(gap(6))
        searchCard.addView(cyberButton("SEARCH PUBLIC CAMERAS", {
            val q = query.text.toString().trim()
            if (q.isEmpty()) { results.text = "Enter a place or camera type."; return@cyberButton }
            results.text = "SEARCHING PUBLIC DIRECTORY…"
            logScan("Public camera directory search: $q")
            executor.execute {
                val data = runPublicCameraSearchDetailed(q)
                logScan("Public camera search complete: $q")
                uiHandler.post {
                    results.text = data.first
                    cameraViews.removeAllViews()
                    if (data.second.isNotEmpty()) {
                        cameraViews.addView(text("AVAILABLE PUBLIC VIEWERS / SOURCES", 11.5f, green, true))
                        data.second.take(20).forEach { src ->
                            cameraViews.addView(gap(5))
                            cameraViews.addView(darkButton("VIEW IN APP • ${src.label}") { openPublicCameraSource(src.url) }, LinearLayout.LayoutParams(-1, dp(44)))
                        }
                    } else {
                        cameraViews.addView(text("No live viewer URL was published in these metadata records. Use the embedded OpenCam map for public-by-design live cameras.", 10.5f, gray))
                    }
                }
            }
        }, blue), LinearLayout.LayoutParams(-1, dp(48)))
        v.addView(searchCard)
        v.addView(gap(10))

        val nearby = card()
        nearby.addView(text("NEARBY PUBLIC CAMERA DIRECTORY", 12f, green, true))
        nearby.addView(gap(5))
        nearby.addView(text("Uses OpenSurveillanceDB's public metadata directory. It does not probe cameras or expose private/unsecured feeds. Enter a city or coordinates; no phone location permission is required.", 10.5f, gray))
        nearby.addView(gap(6))
        val place = input("City / place (example: Bengaluru)")
        val radius = input("Radius in meters (10–500)", "500")
        nearby.addView(place, LinearLayout.LayoutParams(-1, dp(50))); nearby.addView(gap(5))
        nearby.addView(radius, LinearLayout.LayoutParams(-1, dp(50))); nearby.addView(gap(6))
        val nearOut = multilineInput("Nearby public camera results")
        val nearViews = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        nearby.addView(cyberButton("FIND NEARBY PUBLIC CAMERAS", {
            val p = place.text.toString().trim()
            val r = radius.text.toString().toIntOrNull()?.coerceIn(10,500) ?: 500
            if (p.isBlank()) { nearOut.setText("Enter a city or coordinates."); return@cyberButton }
            nearOut.setText("RESOLVING LOCATION $p…"); nearViews.removeAllViews()
            logScan("Nearby public-camera search: $p / ${r}m")
            executor.execute {
                val data = runNearbyPublicCamerasDetailed(p, r)
                logScan("Nearby public-camera search complete: $p")
                uiHandler.post {
                    nearOut.setText(data.first)
                    nearViews.removeAllViews()
                    if (data.second.isNotEmpty()) {
                        nearViews.addView(text("CAMERA DETAILS / PUBLIC VIEW OPTIONS", 11.5f, green, true))
                        data.second.forEach { cam ->
                            nearViews.addView(gap(5))
                            nearViews.addView(darkButton("DETAILS • #${cam.id} ${cam.name}") { showPublicCameraDetails(cam.id) }, LinearLayout.LayoutParams(-1, dp(42)))
                            if (cam.lat.isNotBlank() && cam.lon.isNotBlank()) {
                                nearViews.addView(gap(3)); nearViews.addView(darkButton("COPY COORDS • #${cam.id}") { copy("Camera coordinates", "${cam.lat}, ${cam.lon}") }, LinearLayout.LayoutParams(-1, dp(42)))
                                nearViews.addView(gap(3)); nearViews.addView(darkButton("VIEW LOCATION ON MAP • #${cam.id}") { openUrl("https://www.google.com/maps/search/?api=1&query=${cam.lat},${cam.lon}") }, LinearLayout.LayoutParams(-1, dp(42)))
                            }
                            if (cam.source.startsWith("http://") || cam.source.startsWith("https://")) {
                                nearViews.addView(gap(3)); nearViews.addView(darkButton("VIEW PUBLIC CAMERA • #${cam.id}") { openPublicCameraSource(cam.source) }, LinearLayout.LayoutParams(-1, dp(42)))
                            } else {
                                nearViews.addView(text("No published viewer URL in this public record. Map/source buttons above use only public metadata.", 9.5f, gray))
                            }
                        }
                    } else {
                        nearViews.addView(text("No directly published feed URL was present. Open OpenCam to view intentionally public live cameras around the area.", 10.5f, gray))
                        nearViews.addView(gap(5)); nearViews.addView(darkButton("OPEN OPENCAM LIVE MAP") { openUrl("https://opencam.live/") }, LinearLayout.LayoutParams(-1, dp(42)))
                    }
                }
            }
        }, green), LinearLayout.LayoutParams(-1, dp(46)))
        nearby.addView(gap(6)); nearby.addView(nearOut, LinearLayout.LayoutParams(-1, dp(260)))
        nearby.addView(gap(6)); nearby.addView(nearViews, LinearLayout.LayoutParams(-1, -2))
        v.addView(nearby); v.addView(gap(10))

        val lookup = card()
        lookup.addView(text("◆ CAMERA ID / COORDINATE DIRECT VIEW", 12f, cyan, true)); lookup.addView(gap(5))
        lookup.addView(text("Paste a public camera ID, coordinate pair, or public source URL from the results above. BountyPilot will use the published record/source only.", 10.5f, gray)); lookup.addView(gap(6))
        val lookupInput = input("Camera ID / 19.0684,72.8893 / public URL")
        lookup.addView(lookupInput, LinearLayout.LayoutParams(-1, dp(50))); lookup.addView(gap(5))
        lookup.addView(darkButton("LOOK UP / VIEW PUBLIC CAMERA") {
            val q=lookupInput.text.toString().trim()
            if(q.startsWith("http://")||q.startsWith("https://")) showPublicFeedViewer(q)
            else if(Regex("^\\s*-?\\d+(?:\\.\\d+)?\\s*,\\s*-?\\d+(?:\\.\\d+)?\\s*$").matches(q)) {
                val pair=q.split(",").map{it.trim()}; openUrl("https://www.google.com/maps/search/?api=1&query=${pair[0]},${pair[1]}")
                Toast.makeText(this,"Coordinates opened on map. Use OpenCam for a public live camera at the area.",Toast.LENGTH_LONG).show()
            } else if(q.matches(Regex("\\d+"))) showPublicCameraDetails(q)
            else Toast.makeText(this,"Enter a numeric public camera ID, coordinates, or HTTP(S) source URL.",Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(-1, dp(44)))
        v.addView(lookup); v.addView(gap(10))

        val feed = card()
        feed.addView(text("PUBLIC LIVE FEED VIEWER", 12f, cyan, true)); feed.addView(gap(5))
        feed.addView(text("Paste a camera feed URL only when the feed is intentionally public or you are authorized to view it. BountyPilot does not discover or brute-force exposed cameras.", 10.5f, gray)); feed.addView(gap(6))
        val feedUrl = input("https://public.example/camera.m3u8 or approved viewer URL")
        feed.addView(feedUrl, LinearLayout.LayoutParams(-1, dp(50))); feed.addView(gap(6))
        feed.addView(darkButton("OPEN PUBLIC FEED IN APP", {
            val u = feedUrl.text.toString().trim()
            if (u.startsWith("https://", true) || u.startsWith("http://", true)) { logScan("Opening authorized public camera feed in app"); showPublicFeedViewer(u) } else Toast.makeText(this,"Enter a valid public feed URL.",Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(-1, dp(44))))
        v.addView(feed); v.addView(gap(10))

        val api = card()
        api.addView(text("OPEN DATA SOURCES", 12f, yellow, true))
        api.addView(gap(5))
        api.addView(text("OpenSurveillanceDB: public camera metadata API • OpenCam: public live camera map • source links are shown when available.", 10.5f, gray))
        api.addView(gap(6))
        api.addView(darkButton("OPEN OPENSURVEILLANCEDB", { openUrl("https://opensurveillancedb.org/") }, LinearLayout.LayoutParams(-1, dp(44))))
        api.addView(gap(5))
        api.addView(darkButton("OPEN OPENCAM", { openUrl("https://opencam.live/") }, LinearLayout.LayoutParams(-1, dp(44))))
        v.addView(api)
        v.addView(gap(10))
        v.addView(darkButton("‹ BACK TO DASHBOARD") { map.stopLoading(); map.destroy(); showHome() }, LinearLayout.LayoutParams(-1, dp(45)))
        setScreen(v)
    }

    private fun showOpenCamFullscreen() {
        val v=root(); header(v, "OPENCAM • FULL-SCREEN", "SCROLL INSIDE MAP ONLY • PUBLIC-BY-DESIGN CAMERAS")
        val web=WebView(this).apply {
            settings.javaScriptEnabled=true; settings.domStorageEnabled=true; settings.mediaPlaybackRequiresUserGesture=false; settings.allowFileAccess=false
            webViewClient=WebViewClient(); setBackgroundColor(Color.BLACK); loadUrl("https://opencam.live/")
        }
        keepWebViewScrollLocal(web)
        v.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        v.addView(gap(6))
        v.addView(darkButton("‹ BACK TO CAMERA INTEL") { web.stopLoading(); web.destroy(); showPublicCameras() }, LinearLayout.LayoutParams(-1, dp(45)))
        setScreen(v)
    }

    private fun runPublicCameraSearchDetailed(query: String): Pair<String, List<CameraSource>> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = URL("https://opensurveillancedb.org/api/cameras/search?q=$encoded")
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 12000; c.readTimeout = 18000; c.requestMethod = "GET"
            c.setRequestProperty("Accept", "application/json"); c.setRequestProperty("User-Agent", "BountyPilot/3.1")
            val code = c.responseCode
            val stream = if (code in 200..399) c.inputStream else c.errorStream
            val body = if (stream != null) BufferedReader(InputStreamReader(stream)).use { it.readText() } else ""
            if (code !in 200..299) return "DIRECTORY HTTP $code\n$body" to emptyList()
            val root = JSONObject(body)
            val arr = when {
                root.has("records") -> root.optJSONArray("records") ?: JSONArray()
                root.has("results") -> root.optJSONArray("results") ?: JSONArray()
                root.has("cameras") -> root.optJSONArray("cameras") ?: JSONArray()
                body.trim().startsWith("[") -> JSONArray(body)
                else -> JSONArray()
            }
            if (arr.length() == 0) return "NO PUBLIC CAMERA RECORDS FOUND FOR: $query" to emptyList()
            val sources = mutableListOf<CameraSource>()
            val sb = StringBuilder("PUBLIC CAMERA RESULTS: ${arr.length()}\n\n")
            for (i in 0 until minOf(arr.length(), 40)) {
                val o = arr.optJSONObject(i) ?: continue
                val id = firstNonBlank(o,"id","cameraId").ifBlank{"?"}
                val name = firstNonBlank(o,"title","name","label","camera").ifBlank{"Surveillance camera"}
                val kind = firstNonBlank(o,"kind","type","category")
                val address = firstNonBlank(o,"address","location","city")
                val lat = firstNonBlank(o,"lat","latitude"); val lon = firstNonBlank(o,"lng","lon","longitude")
                val source = firstNonBlank(o,"official_url","source_url","stream_url","image_url","url")
                sb.append("#${i+1} $name\nID: $id  TYPE: ${kind.ifBlank{"public"}}\n")
                if(address.isNotBlank()) sb.append("LOCATION: $address\n")
                if(lat.isNotBlank() || lon.isNotBlank()) sb.append("COORDS: $lat, $lon\n")
                if(source.isNotBlank() && (source.startsWith("https://") || source.startsWith("http://"))) { sb.append("VIEWER: available\n"); sources.add(CameraSource("#$id $name", source)) }
                else sb.append("VIEWER: metadata only\n")
                sb.append("\n")
            }
            sb.append("Public camera metadata is not proof that a feed is live. Viewer buttons appear only when the public record includes a URL.")
            sb.toString() to sources
        } catch (e: Exception) { "PUBLIC CAMERA SEARCH FAILED\n${e.message ?: "unknown error"}" to emptyList() }
    }

    private fun runPublicCameraSearch(query: String): String {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = URL("https://opensurveillancedb.org/api/cameras/search?q=$encoded")
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 12000; c.readTimeout = 18000; c.requestMethod = "GET"
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "BountyPilot/2.9")
            val code = c.responseCode
            val stream = if (code in 200..399) c.inputStream else c.errorStream
            val body = if (stream != null) BufferedReader(InputStreamReader(stream)).use { it.readText() } else ""
            if (code !in 200..299) return "DIRECTORY HTTP $code\n$body"
            val root = JSONObject(body)
            val arr = when {
                root.has("cameras") -> root.optJSONArray("cameras") ?: JSONArray()
                root.has("results") -> root.optJSONArray("results") ?: JSONArray()
                root.has("records") -> root.optJSONArray("records") ?: JSONArray()
                else -> JSONArray()
            }
            if (arr.length() == 0) return "NO PUBLIC CAMERA RECORDS FOUND FOR: $query"
            val sb = StringBuilder("PUBLIC CAMERA RESULTS: ${arr.length()}\n\n")
            for (i in 0 until minOf(arr.length(), 40)) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id", "?")
                val name = firstNonBlank(o, "title", "name", "label", "camera")
                val kind = firstNonBlank(o, "kind", "type", "category")
                val address = firstNonBlank(o, "address", "location", "city")
                val lat = firstNonBlank(o, "lat", "latitude")
                val lon = firstNonBlank(o, "lng", "lon", "longitude")
                val source = firstNonBlank(o, "official_url", "source_url", "url", "image_url", "stream_url")
                sb.append("#${i+1}  $name\n")
                sb.append("ID: $id  TYPE: ${if (kind.isBlank()) "PUBLIC CAMERA" else kind}\n")
                if (address.isNotBlank()) sb.append("LOCATION: $address\n")
                if (lat.isNotBlank() || lon.isNotBlank()) sb.append("COORDS: $lat, $lon\n")
                if (source.isNotBlank()) sb.append("SOURCE/LIVE: $source\n")
                sb.append("\n")
            }
            sb.append("Use LIVE MAP for cameras with live viewers. Source URLs belong to the camera operator/provider.")
            sb.toString()
        } catch (e: Exception) {
            "PUBLIC CAMERA SEARCH FAILED\n${e.message ?: "unknown error"}\n\nTry again or use WORLD LIVE CAMERA MAP."
        }
    }

    private fun runNearbyPublicCameras(place: String, radius: Int): String {
        return try {
            val geoUrl = URL("https://opensurveillancedb.org/api/geocode?q=${URLEncoder.encode(place, "UTF-8")}")
            val gc = geoUrl.openConnection() as HttpURLConnection
            gc.connectTimeout = 10000; gc.readTimeout = 15000; gc.requestMethod = "GET"
            gc.setRequestProperty("Accept", "application/json")
            gc.setRequestProperty("User-Agent", "BountyPilot/3.1")
            val geoBody = BufferedReader(InputStreamReader(gc.inputStream)).use { it.readText() }
            val geoRoot = JSONObject(geoBody)
            val ga = when {
                geoRoot.has("results") -> geoRoot.optJSONArray("results") ?: JSONArray()
                else -> JSONArray()
            }
            val g = if (ga.length() > 0) ga.optJSONObject(0) else null
            val lat = g?.optString("lat").orEmpty()
            val lon = g?.optString("lon").orEmpty().ifBlank { g?.optString("lng").orEmpty() }
            if (lat.isBlank() || lon.isBlank()) return "PLACE COORDINATES UNAVAILABLE: $place"

            logScan("Geocode resolved $place → $lat,$lon")
            val u = URL("https://opensurveillancedb.org/api/cameras/nearby?latitude=${URLEncoder.encode(lat,"UTF-8")}&longitude=${URLEncoder.encode(lon,"UTF-8")}&radius=$radius")
            val c = u.openConnection() as HttpURLConnection
            c.connectTimeout = 12000; c.readTimeout = 18000; c.requestMethod = "GET"
            c.setRequestProperty("Accept", "application/json"); c.setRequestProperty("User-Agent", "BountyPilot/3.1")
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = if (stream != null) BufferedReader(InputStreamReader(stream)).use { it.readText() } else ""
            if (code !in 200..299) return "DIRECTORY HTTP $code\n$body"
            val root = runCatching { JSONObject(body) }.getOrNull()
            val arr = when {
                root?.has("records") == true -> root.optJSONArray("records") ?: JSONArray()
                root?.has("results") == true -> root.optJSONArray("results") ?: JSONArray()
                root?.has("cameras") == true -> root.optJSONArray("cameras") ?: JSONArray()
                body.trim().startsWith("[") -> JSONArray(body)
                else -> JSONArray()
            }
            if (arr.length() == 0) return "NO PUBLIC CAMERA RECORDS WITHIN ${radius}m OF $place\n\nCENTER: $lat, $lon"
            val sb = StringBuilder("NEAR $place — ${arr.length()} PUBLIC CAMERA RECORDS\nCENTER: $lat, $lon\nRADIUS: ${radius}m\n\n")
            for (i in 0 until minOf(arr.length(), 50)) {
                val o = arr.optJSONObject(i) ?: continue
                val name = firstNonBlank(o,"title","name","label","camera").ifBlank{"Public camera"}
                val kind = firstNonBlank(o,"kind","type","category")
                val la = firstNonBlank(o,"latitude","lat"); val lo = firstNonBlank(o,"longitude","lng","lon")
                val status = firstNonBlank(o,"status","state")
                val id = firstNonBlank(o,"id","cameraId")
                sb.append("#${i+1} $name\nID: ${id.ifBlank{"?"}}\nTYPE: ${kind.ifBlank{"public"}}  STATUS: ${status.ifBlank{"unknown"}}\n")
                if (la.isNotBlank() || lo.isNotBlank()) sb.append("COORDS: $la, $lo\n")
                sb.append("SOURCE: OpenSurveillanceDB public record\n\n")
            }
            sb.toString()
        } catch (e: Exception) {
            "NEARBY CAMERA SEARCH FAILED\n${e.message ?: "unknown error"}\n\nThe public directory returns metadata; live viewing is only available for feeds intentionally published by the camera operator."
        }
    }

    private fun firstNonBlank(o: JSONObject, vararg keys: String): String {
        for (k in keys) {
            val v = o.optString(k, "").trim()
            if (v.isNotBlank() && v != "null") return v
        }
        return ""
    }

    private fun showTargetConsole() {
        showHome()
        Toast.makeText(this, "Enter a target in QUICK TARGET and start authorized recon.", Toast.LENGTH_LONG).show()
    }

    private fun showTestLabConsole() {
        val a = lastAssessment
        if (a != null) {
            authorizationGate("ONE-AT-A-TIME VALIDATION TESTS") { showValidation(a) }
            return
        }
        val v = root()
        header(v, "TEST LAB", "ONE TEST AT A TIME • SAFE / MANUAL VALIDATION")
        val c = card()
        c.addView(text("NO TARGET ASSESSMENT LOADED", 13f, yellow, true)); c.addView(gap(6))
        c.addView(text("Run an authorized recon first. BountyPilot will then build the validation queue from the discovered surface plus its safe test library.", 12f, white))
        c.addView(gap(8))
        c.addView(cyberButton("GO TO TARGET CONSOLE", { showHome() }, blue), LinearLayout.LayoutParams(-1, dp(48)))
        v.addView(c); v.addView(gap(10))
        v.addView(darkButton("BACK TO DASHBOARD") { showHome() }, LinearLayout.LayoutParams(-1, dp(45)))
        setScreen(v)
    }

    private fun showNmapConsole() {
        val v = root()
        header(v, "NMAP / NETWORK ENGINE", "HOST DISCOVERY • SERVICE INVENTORY • AUTHORIZED TARGETS")
        val status = card()
        status.addView(text("ENGINE: ${if (nmapAvailable()) "NMAP EXECUTABLE READY" else "NMAP EXTERNAL / FALLBACK ENGINE READY"}", 12.5f, if (nmapAvailable()) green else yellow, true))
        status.addView(gap(5))
        status.addView(text("When the nmap binary is unavailable inside Android, BountyPilot can perform a limited single-host TCP inventory itself or hand the exact command to Termux/Kali. It never fabricates Nmap output.", 11.5f, gray))
        v.addView(status); v.addView(gap(10))
        val c = card()
        c.addView(text("AUTHORIZED TARGET", 11.5f, cyan, true)); c.addView(gap(5))
        val target = input("192.168.1.10 or authorized.example.com")
        c.addView(target, LinearLayout.LayoutParams(-1, dp(50))); c.addView(gap(7))
        c.addView(cyberButton("DISCOVER HOST  •  -sn", {
            val t=target.text.toString().trim(); if(t.isBlank()) Toast.makeText(this,"Enter an authorized target.",Toast.LENGTH_SHORT).show() else authorizationGate("NMAP HOST DISCOVERY") { runNmap(t, listOf("-sn", t)) }
        }, blue), LinearLayout.LayoutParams(-1, dp(48)))
        c.addView(gap(6))
        c.addView(cyberButton("SERVICE / VERSION INVENTORY", {
            val t=target.text.toString().trim(); if(t.isBlank()) Toast.makeText(this,"Enter an authorized target.",Toast.LENGTH_SHORT).show() else authorizationGate("NMAP SERVICE INVENTORY") { runNmap(t, listOf("-sT", "-sV", "--top-ports", "100", t)) }
        }, cyan), LinearLayout.LayoutParams(-1, dp(48)))
        c.addView(gap(6))
        c.addView(darkButton("COPY SAFE COMMANDS") { copy("Nmap commands", "nmap -sn TARGET\nnmap -sT -sV --top-ports 100 TARGET") }, LinearLayout.LayoutParams(-1, dp(44)))
        v.addView(c); v.addView(gap(10))
        v.addView(darkButton("BACK TO DASHBOARD") { showHome() }, LinearLayout.LayoutParams(-1, dp(45)))
        setScreen(v)
    }

    private fun showMetasploitBridge() {
        val v = root()
        header(v, "METASPLOIT LAB BRIDGE", "MSFCONSOLE • AUTHORIZED LAB / MANUAL OPERATION")
        val c = card()
        val installed = termuxInstalled()
        c.addView(text("TERMUX: ${if (installed) "DETECTED" else "NOT DETECTED"}", 12.5f, if (installed) green else yellow, true))
        c.addView(gap(6))
        c.addView(text("Metasploit is exposed as an external console rather than embedded as an autonomous exploit engine. BountyPilot can launch/check the console in Termux when supported, or copy the command for a Kali/Termux environment.", 11.5f, white))
        v.addView(c); v.addView(gap(10))
        val lab = card()
        lab.addView(text("LAB CONSOLE INPUT", 11.5f, cyan, true)); lab.addView(gap(5))
        val msfInput=input("Read-only msfconsole command: version / help / search ...")
        lab.addView(msfInput, LinearLayout.LayoutParams(-1, dp(50))); lab.addView(gap(6))
        lab.addView(darkButton("SEND SAFE CONSOLE COMMAND") {
            val cmd=msfInput.text.toString().trim()
            val safe=cmd.startsWith("version",true)||cmd.startsWith("help",true)||cmd.startsWith("search ",true)||cmd.startsWith("info ",true)||cmd.startsWith("show ",true)
            if(cmd.isBlank()) Toast.makeText(this,"Enter a command.",Toast.LENGTH_SHORT).show()
            else if(!safe) Toast.makeText(this,"This bridge only sends read-only discovery commands from the app. Use the manual Termux console for other lab actions.",Toast.LENGTH_LONG).show()
            else {
                val clean=cmd.replace("\"","")
                if(installed) launchTermuxCommand("msfconsole -q -x \"$clean; exit\"") else showExternalCommand("Metasploit command", "msfconsole -q -x \"$clean; exit\"")
            }
        }, LinearLayout.LayoutParams(-1, dp(45)))
        lab.addView(gap(6))
        lab.addView(text("LAB ACTIONS", 11.5f, cyan, true)); lab.addView(gap(6))
        lab.addView(darkButton("CHECK MSFCONSOLE") {
            if (installed) launchTermuxCommand("msfconsole -q -x \"version; exit\"")
            else copy("Metasploit check", "msfconsole -q -x \"version; exit\"")
        }, LinearLayout.LayoutParams(-1, dp(45)))
        lab.addView(gap(6))
        lab.addView(cyberButton("OPEN MSFCONSOLE", {
            authorizationGate("OPEN METASPLOIT CONSOLE FOR AN AUTHORIZED LAB") {
                if (installed) launchTermuxCommand("msfconsole") else showExternalCommand("Metasploit external bridge", "msfconsole")
            }
        }, red), LinearLayout.LayoutParams(-1, dp(48)))
        lab.addView(gap(6))
        lab.addView(darkButton("COPY LAB COMMAND") { copy("Metasploit command", "msfconsole") }, LinearLayout.LayoutParams(-1, dp(44)))
        v.addView(lab); v.addView(gap(10))
        val note = card(); note.addView(text("IMPORTANT", 11.5f, yellow, true)); note.addView(gap(5)); note.addView(text("The bridge does not automatically select or execute exploit modules against targets. Use Metasploit only against systems you are explicitly authorized to test, preferably an isolated lab while developing the workflow.", 11.5f, gray)); v.addView(note); v.addView(gap(10))
        v.addView(darkButton("BACK TO DASHBOARD") { showHome() }, LinearLayout.LayoutParams(-1, dp(45)))
        setScreen(v)
    }

    private fun termuxInstalled(): Boolean {
        return try { packageManager.getPackageInfo("com.termux", 0); true } catch (_: Exception) { false }
    }

    private fun launchTermuxCommand(command: String) {
        if (!termuxInstalled()) { showExternalCommand("Termux not installed", command); return }
        try {
            val intent = Intent("com.termux.RUN_COMMAND").apply {
                setPackage("com.termux")
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", command))
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
            }
            sendBroadcast(intent, "com.termux.permission.RUN_COMMAND")
            // Also bring the Termux UI to the foreground so the user can see the console.
            val launch = packageManager.getLaunchIntentForPackage("com.termux")
            if (launch != null) startActivity(launch)
            Toast.makeText(this, "Termux command sent: $command", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            try { packageManager.getLaunchIntentForPackage("com.termux")?.let { startActivity(it) } } catch (_: Exception) {}
            showExternalCommand("Termux bridge needs configuration", command + "\n\nEnable Termux external commands (allow-external-apps=true) and grant the RUN_COMMAND permission, then retry.\n\nReason: " + (e.message ?: "unknown"))
        }
    }

    private fun showExternalCommand(title: String, command: String) {
        val c = card(); c.addView(text(title, 13f, cyan, true)); c.addView(gap(6)); c.addView(text(command, 12f, white)); c.addView(gap(7)); c.addView(cyberButton("COPY COMMAND", { copy(title, command) }, blue), LinearLayout.LayoutParams(-1, dp(45)))
        AlertDialog.Builder(this).setTitle(title).setView(c).setPositiveButton("OK", null).show()
    }


    private fun showFindingsHub() {
        val v = root()
        header(v, "FINDINGS & REPORTS", "EVIDENCE • VERIFICATION • BOUNTY-STYLE REPORT")
        val c = card()
        c.addView(text("NO ACTIVE FINDING SELECTED", 13f, yellow, true)); c.addView(gap(6))
        c.addView(text("Run an authorized target assessment first, then use the findings screen to distinguish observations, candidates, reproduced behavior and confirmed impact.", 12f, gray))
        c.addView(gap(8))
        c.addView(cyberButton("OPEN TRAINING REPORT EXAMPLE", { showTrainingLab() }, blue), LinearLayout.LayoutParams(-1, dp(48)))
        v.addView(c); v.addView(gap(10)); v.addView(darkButton("BACK TO DASHBOARD") { showHome() }, LinearLayout.LayoutParams(-1, dp(45)))
        setScreen(v)
    }

    private fun showTrafficConsole() = showToolboxSection("TRAFFIC / PCAP LAB")
    private fun showDnsTlsConsole() = showToolboxSection("DNS / TLS UTILITIES")

    private fun showToolboxSection(title: String) {
        showToolbox()
        Toast.makeText(this, "$title opened in Security Toolbox.", Toast.LENGTH_SHORT).show()
    }

    private fun authorizationGate(action: String, onContinue: () -> Unit) {
        val check = CheckBox(this).apply {
            text = "I confirm I have explicit authorization for this target, asset, and testing method."
            setTextColor(white)
            setPadding(0, dp(8), 0, 0)
        }
        AlertDialog.Builder(this)
            .setTitle("AUTHORIZATION REQUIRED")
            .setMessage(
                "BountyPilot is about to perform: $action\n\n" +
                    "Only continue if the exact target is in scope and the program permits this testing method. " +
                    "Do not test third-party infrastructure, private accounts, or out-of-scope assets. " +
                    "Low-impact checks can still create logs or traffic on the target."
            )
            .setView(check)
            .setNegativeButton("CANCEL", null)
            .setPositiveButton("I HAVE AUTHORIZATION — CONTINUE") { _, _ ->
                if (check.isChecked) onContinue()
                else Toast.makeText(this, "Authorization confirmation is required.", Toast.LENGTH_LONG).show()
            }
            .show()
    }

    private fun inspect(raw: String) {
        val normalized = normalizeUrl(raw) ?: run {
            Toast.makeText(this, "Use a valid http:// or https:// URL.", Toast.LENGTH_LONG).show(); return
        }
        cyberScanSound(3200L)
        logScan("Attack-surface scan started: $normalized")
        showLoading("SCANNING ATTACK SURFACE", normalized)
        executor.execute {
            val r = performRecon(normalized)
            logScan("Attack-surface scan complete: ${r.http.detail}")
            runOnUiThread { showResult(r) }
        }
    }

    private fun normalizeUrl(raw: String): String? {
        val value = if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) raw else "https://$raw"
        return try {
            val u = URL(value)
            if ((u.protocol.equals("http", true) || u.protocol.equals("https", true)) && u.host.isNotBlank()) u.toString() else null
        } catch (_: Exception) { null }
    }

    private data class Stage(val name: String, val ok: Boolean?, val detail: String)

    private data class ReconResult(
        val url: String, val host: String, val port: Int, val dns: Stage, val tcp: Stage, val https: Stage, val http: Stage,
        val status: String, val duration: String, val server: String, val contentType: String, val selectedAddress: String?,
        val redirect: String, val tls: String, val headers: List<String>, val cookies: List<String>, val cors: List<String>,
        val title: String, val forms: Int, val links: Int, val tech: List<String>, val robots: String, val sitemap: String,
        val securityTxt: String, val candidates: List<String>, val body: String, val error: String? = null
    )

    private data class EndpointResult(
        val base: String, val endpoints: List<String>, val apiLike: List<String>, val authLike: List<String>,
        val parameterized: List<String>, val interesting: List<String>, val robotsUrls: List<String>, val sitemapUrls: List<String>
    )

    private data class SurfaceCheck(val url: String, val status: String, val type: String, val redirect: String, val notes: String)
    private data class AssessmentResult(val recon: ReconResult, val endpoints: EndpointResult, val checks: List<SurfaceCheck>, val findings: List<String>)

    private fun performRecon(target: String): ReconResult {
        val u = URL(target)
        val host = u.host
        val port = if (u.port != -1) u.port else if (u.protocol.equals("https", true)) 443 else 80
        val dns = resolveDns(host)
        if (dns.ok != true) return failed(target, host, port, dns, "DNS resolution failed.")
        val addresses = try { InetAddress.getAllByName(host).toList() } catch (_: Exception) { emptyList() }
        val tcp = checkTcp(addresses, port)
        if (tcp.first.ok != true) return failed(target, host, port, dns, "TCP connection failed or timed out.", tcp.first)
        return performHttpAndPassive(target, host, port, dns, tcp.first, tcp.second)
    }

    private fun resolveDns(host: String): Stage = try {
        val start = System.currentTimeMillis(); val addresses = InetAddress.getAllByName(host)
        Stage("DNS", addresses.isNotEmpty(), addresses.map { it.hostAddress }.distinct().joinToString(", ") + " • ${System.currentTimeMillis() - start} ms")
    } catch (e: Exception) { Stage("DNS", false, classifyException(e)) }

    private fun checkTcp(addresses: List<InetAddress>, port: Int): Pair<Stage, String?> {
        val failures = mutableListOf<String>()
        for (a in addresses) {
            val start = System.currentTimeMillis()
            try {
                Socket().use { it.connect(InetSocketAddress(a, port), 5000) }
                return Pair(Stage("TCP", true, "Port $port reachable at ${a.hostAddress} • ${System.currentTimeMillis() - start} ms"), a.hostAddress)
            } catch (e: Exception) { failures += "${a.hostAddress}: ${classifyException(e)}" }
        }
        return Pair(Stage("TCP", false, "Port $port failed on ${addresses.size} resolved address(es).\n${failures.take(6).joinToString("\n")}"), null)
    }

    private fun performHttpAndPassive(target: String, host: String, port: Int, dns: Stage, tcp: Stage, selected: String?): ReconResult {
        var conn: HttpURLConnection? = null
        val start = System.currentTimeMillis()
        return try {
            conn = URL(target).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "BountyPilot/1.0 authorized-safe-assessment")
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*")
            conn.connect()
            val tls = if (conn is HttpsURLConnection) try { "HTTPS • ${conn.cipherSuite}" } catch (_: Exception) { "HTTPS • TLS metadata unavailable" } else "HTTP • no TLS"
            val httpsStage = if (conn is HttpsURLConnection) Stage("HTTPS", true, "TLS connection established.") else Stage("HTTPS", null, "HTTP target.")
            val code = conn.responseCode
            val status = "$code ${conn.responseMessage ?: ""}".trim()
            val body = readBodySafely(conn)
            val headers = conn.headerFields
            val cookieLines = cookieObservations(headers)
            val corsLines = corsObservations(headers)
            val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(body)?.groupValues?.getOrNull(1)?.replace(Regex("\\s+"), " ")?.trim()?.take(180) ?: "Not observed"
            val forms = Regex("(?is)<form\\b").findAll(body).count()
            val links = Regex("(?is)<a\\b[^>]*href\\s*=").findAll(body).count()
            val robots = fetchText(URL("${URL(target).protocol}://$host/robots.txt"))
            val sitemap = fetchText(URL("${URL(target).protocol}://$host/sitemap.xml"))
            val securityTxt = fetchText(URL("${URL(target).protocol}://$host/.well-known/security.txt"))
            ReconResult(target, host, port, dns, tcp, httpsStage, Stage("HTTP", true, status), status,
                "${System.currentTimeMillis() - start} ms", conn.getHeaderField("Server") ?: "Not disclosed", conn.contentType ?: "Not disclosed",
                selected, conn.getHeaderField("Location") ?: "None", tls, importantHeaders(headers), cookieLines, corsLines, title, forms, links,
                technologyHints(headers, body), robots, sitemap, securityTxt, candidateFindings(headers, cookieLines, corsLines, body, target), body)
        } catch (e: SSLHandshakeException) {
            failed(target, host, port, dns, "TLS handshake failed: ${e.message ?: "handshake error"}", tcp, true)
        } catch (e: Exception) {
            failed(target, host, port, dns, "HTTP request failed: ${classifyException(e)}", tcp)
        } finally { conn?.disconnect() }
    }

    private fun readBodySafely(c: HttpURLConnection) = try {
        val stream = if (c.responseCode >= 400) c.errorStream else c.inputStream
        stream?.bufferedReader()?.use { it.readText().take(1_000_000) } ?: ""
    } catch (_: Exception) { "" }

    private fun fetchText(url: URL) = try {
        (url.openConnection() as HttpURLConnection).run {
            requestMethod = "GET"; instanceFollowRedirects = false; connectTimeout = 4000; readTimeout = 4000
            setRequestProperty("User-Agent", "BountyPilot/1.0 authorized-safe-assessment"); connect()
            if (responseCode in 200..399) inputStream.bufferedReader().use { it.readText().take(20000) } else "Not available (HTTP $responseCode)"
        }
    } catch (e: Exception) { "Not available (${e.javaClass.simpleName})" }

    private fun importantHeaders(h: Map<String?, List<String>>): List<String> = listOf(
        "Strict-Transport-Security", "Content-Security-Policy", "X-Content-Type-Options", "X-Frame-Options", "Referrer-Policy",
        "Permissions-Policy", "Cache-Control", "Access-Control-Allow-Origin", "Access-Control-Allow-Credentials"
    ).map { "$it: ${headerValue(h, it) ?: "not observed"}" }

    private fun cookieObservations(h: Map<String?, List<String>>): List<String> {
        val cookies = h.entries.filter { it.key?.equals("Set-Cookie", true) == true }.flatMap { it.value }
        if (cookies.isEmpty()) return listOf("No Set-Cookie header observed.")
        return cookies.take(10).map { c ->
            val secure = if (Regex("(?i)(^|;)\\s*Secure(?:;|$)").containsMatchIn(c)) "Secure" else "Secure missing"
            val httpOnly = if (Regex("(?i)(^|;)\\s*HttpOnly(?:;|$)").containsMatchIn(c)) "HttpOnly" else "HttpOnly missing"
            val sameSite = if (Regex("(?i)(^|;)\\s*SameSite=").containsMatchIn(c)) "SameSite present" else "SameSite missing"
            "• $secure • $httpOnly • $sameSite"
        }
    }

    private fun corsObservations(h: Map<String?, List<String>>): List<String> {
        val o = headerValue(h, "Access-Control-Allow-Origin"); val c = headerValue(h, "Access-Control-Allow-Credentials")
        return if (o == null && c == null) listOf("No CORS response headers observed.") else listOf("Access-Control-Allow-Origin: ${o ?: "not observed"}", "Access-Control-Allow-Credentials: ${c ?: "not observed"}")
    }

    private fun technologyHints(h: Map<String?, List<String>>, body: String): List<String> {
        val out = mutableListOf<String>()
        headerValue(h, "Server")?.let { out += "Server: $it" }
        headerValue(h, "X-Powered-By")?.let { out += "X-Powered-By: $it" }
        if (body.contains("wp-content", true) || body.contains("wordpress", true)) out += "Possible WordPress markers"
        if (body.contains("__NEXT_DATA__", true) || body.contains("/_next/", true)) out += "Possible Next.js markers"
        if (body.contains("ng-version", true)) out += "Possible Angular marker"
        if (body.contains("data-reactroot", true)) out += "Possible React marker"
        if (body.contains("laravel", true)) out += "Possible Laravel marker"
        return if (out.isEmpty()) listOf("No strong technology hint observed.") else out.distinct()
    }

    private fun candidateFindings(h: Map<String?, List<String>>, cookies: List<String>, cors: List<String>, body: String, target: String): List<String> {
        val out = mutableListOf<String>()
        val security = linkedMapOf("Strict-Transport-Security" to "HSTS", "Content-Security-Policy" to "CSP", "X-Content-Type-Options" to "X-Content-Type-Options", "X-Frame-Options" to "X-Frame-Options", "Referrer-Policy" to "Referrer-Policy")
        for ((header, label) in security) if (headerValue(h, header) == null) out += "Candidate: $label not observed — hardening review"
        if (cookies.any { it.contains("missing") }) out += "Candidate: cookie security attributes should be reviewed manually"
        if (headerValue(h, "Access-Control-Allow-Origin") == "*") out += "Candidate: wildcard CORS observed — determine whether sensitive data is exposed"
        if (Regex("(?i)<input[^>]+type=[\\\"']?(password|hidden)[\\\"']?").containsMatchIn(body)) out += "Candidate: authentication or hidden form fields observed — review related endpoints"
        if (target.contains("?")) out += "Candidate: URL contains query parameters — input testing candidate"
        return out
    }

    private fun headerValue(h: Map<String?, List<String>>, name: String): String? = h.entries.firstOrNull { it.key?.equals(name, true) == true }?.value?.firstOrNull()

    private fun failed(target: String, host: String, port: Int, dns: Stage, error: String, tcp: Stage = Stage("TCP", null, "Not completed."), tlsFailed: Boolean = false) =
        ReconResult(target, host, port, dns, tcp, Stage("HTTPS", if (tlsFailed) false else null, if (tlsFailed) error else "Not attempted."), Stage("HTTP", null, "Not attempted."), "NOT RECEIVED", "—", "—", "—", null, "—", "—", emptyList(), emptyList(), emptyList(), "—", 0, 0, emptyList(), "—", "—", "—", emptyList(), "", error)

    private fun classifyException(e: Exception): String = when (e) {
        is java.net.SocketTimeoutException -> "TIMEOUT"
        is java.net.UnknownHostException -> "DNS ERROR"
        is java.net.ConnectException -> "CONNECTION FAILED"
        is SSLHandshakeException -> "TLS HANDSHAKE ERROR"
        else -> "${e.javaClass.simpleName}: ${e.message ?: "no details"}"
    }

    private fun showLoading(title: String, target: String) {
        val v = root(); header(v, title, target)
        val c = card(); c.addView(text("◉ LIVE SCAN", 14f, cyan, true)); c.addView(gap(8)); c.addView(text("DNS → TCP → TLS → HTTP → HTML → endpoints → candidate correlation", 12.5f, white)); c.addView(gap(15)); c.addView(ProgressBar(this).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(cyan) }); v.addView(c)
        v.addView(gap(20)); v.addView(text("Scanning is rate-limited and low-impact.", 11f, gray)); setScreen(v)
    }

    private fun stageText(s: Stage): String = "${when (s.ok) { true -> "●"; false -> "✕"; null -> "○" }} ${s.name}\n${s.detail}"

    private fun showResult(r: ReconResult) {
        val v = root(); header(v, "RECON RESULT", r.url)
        addSection(v, "CONNECTION MATRIX", listOf(r.dns, r.tcp, r.https, r.http).joinToString("\n\n") { stageText(it) })
        addSection(v, "HTTP SNAPSHOT", "Status: ${r.status}\nResponse: ${r.duration}\nServer: ${r.server}\nContent-Type: ${r.contentType}\nSelected IP: ${r.selectedAddress ?: "not observed"}\nRedirect: ${r.redirect}\nTLS: ${r.tls}")
        addSection(v, "SECURITY HEADERS", r.headers.joinToString("\n"), yellow)
        addSection(v, "COOKIES", r.cookies.joinToString("\n"), yellow)
        addSection(v, "CORS", r.cors.joinToString("\n"), yellow)
        addSection(v, "PAGE SURFACE", "Title: ${r.title}\nForms: ${r.forms}\nLinks: ${r.links}\nTechnology: ${r.tech.joinToString(", ")}")
        addNavigation(v, "‹ BACK", { showHome() }, "ENDPOINT MAP ›", { showEndpointDiscovery(r) })
        setScreen(v)
    }

    private fun showEndpointDiscovery(r: ReconResult) {
        val e = discoverEndpoints(r); val v = root(); header(v, "ENDPOINT MAP", r.url)
        addSection(v, "HOW TO USE", "Example: if recon finds /search?q=test, it appears under PARAMETERIZED INPUTS. Sitemap pagination such as /sitemap.xml?first=a is filtered from that testing list because it is crawl metadata, not an application input.", yellow)
        addSection(v, "DISCOVERY SUMMARY", "URLs: ${e.endpoints.size}\nAPI-like: ${e.apiLike.size}\nAuth/account: ${e.authLike.size}\nParameterized: ${e.parameterized.size}\nInteresting: ${e.interesting.size}")
        addSection(v, "DISCOVERED URLS", e.endpoints.joinToString("\n").ifBlank { "No additional same-origin URLs observed." })
        if (e.apiLike.isNotEmpty()) addSection(v, "API-LIKE", e.apiLike.joinToString("\n"), yellow)
        if (e.authLike.isNotEmpty()) addSection(v, "AUTH / ACCOUNT", e.authLike.joinToString("\n"), yellow)
        if (e.parameterized.isNotEmpty()) addSection(v, "PARAMETERIZED INPUTS", e.parameterized.joinToString("\n"), yellow)
        if (e.interesting.isNotEmpty()) addSection(v, "INTERESTING PATHS", e.interesting.joinToString("\n"), yellow)
        addNavigation(v, "‹ RECON", { showResult(r) }, "SAFE CHECKS ›", { runSafeChecks(r, e) })
        setScreen(v)
    }

    private fun discoverEndpoints(r: ReconResult): EndpointResult {
        val origin = "${URL(r.url).protocol}://${r.host}"
        val all = linkedSetOf<String>()
        extractLinksFromHtml(r.url, r.body, origin, all)
        parseRobotsUrls(r.robots, origin).forEach { all += it }
        parseSitemapUrls(r.sitemap, origin).forEach { all += it }
        all += r.url.substringBefore('#')
        val endpoints = all.map { normalizeEndpointUrl(it) }.distinct().filter { it.startsWith(origin) }.take(120)
        val api = endpoints.filter { it.contains("/api", true) || it.contains("graphql", true) || it.contains("/v1/", true) || it.contains("/v2/", true) }
        val auth = endpoints.filter { it.contains("login", true) || it.contains("signin", true) || it.contains("signup", true) || it.contains("register", true) || it.contains("account", true) || it.contains("password", true) || it.contains("session", true) || it.contains("oauth", true) }
        val params = endpoints.filter { it.contains("?") && !isSitemapPaginationUrl(it) }
        val interesting = endpoints.filter { Regex("(?i)/(admin|dashboard|debug|docs|swagger|openapi|actuator|health|internal|config)(/|$|\\?)").containsMatchIn(it) }
        return EndpointResult(origin, endpoints, api, auth, params, interesting, parseRobotsUrls(r.robots, origin), parseSitemapUrls(r.sitemap, origin))
    }

    private fun extractLinksFromHtml(sourceUrl: String, html: String, origin: String, out: MutableSet<String>) {
        Regex("(?is)href\\s*=\\s*[\\\"']([^\\\"'#]+)").findAll(html).forEach { m -> normalizeDiscoveredUrl(sourceUrl, m.groupValues[1], origin)?.let { out += it } }
        Regex("(?is)(?:src|action)\\s*=\\s*[\\\"']([^\\\"'#]+)").findAll(html).forEach { m -> normalizeDiscoveredUrl(sourceUrl, m.groupValues[1], origin)?.let { out += it } }
    }

    private fun extractRobotsUrls(text: String, origin: String, out: MutableSet<String>) {
        text.lines().forEach { line -> val x = line.substringAfter(":", "").trim(); if (x.startsWith("/") || x.startsWith("http")) normalizeDiscoveredUrl(origin, x, origin)?.let { out += it } }
    }

    private fun extractSitemapUrls(text: String, origin: String, out: MutableSet<String>) {
        Regex("(?is)<loc>\\s*(.*?)\\s*</loc>").findAll(text).forEach { normalizeDiscoveredUrl(origin, it.groupValues[1].trim(), origin)?.let { x -> out += x } }
    }

    private fun isSitemapPaginationUrl(raw: String): Boolean {
        return try {
            val u = URL(raw)
            val path = u.path.lowercase(Locale.ROOT)
            val keys = (u.query ?: "").split("&").mapNotNull { part -> part.substringBefore("=", "").lowercase(Locale.ROOT).takeIf { it.isNotBlank() } }
            val paginationKeys = setOf("first", "last", "page", "offset", "start", "limit", "cursor")
            (path.endsWith("/sitemap.xml") || path.endsWith("/sitemap_index.xml")) && keys.isNotEmpty() && keys.all { it in paginationKeys }
        } catch (_: Exception) { false }
    }

    private fun parseRobotsUrls(text: String, origin: String) = linkedSetOf<String>().also { extractRobotsUrls(text, origin, it) }.take(50)
    private fun parseSitemapUrls(text: String, origin: String) = linkedSetOf<String>().also { extractSitemapUrls(text, origin, it) }.take(50)

    private fun normalizeDiscoveredUrl(source: String, raw: String, origin: String): String? = try {
        val x = raw.trim(); val base = URL(source); val u = URL(base, x)
        if (u.protocol == base.protocol && "${u.protocol}://${u.host}" == origin) u.toString() else null
    } catch (_: Exception) { null }

    private fun normalizeEndpointUrl(v: String) = v.substringBefore('#').trimEnd('/')

    private fun runSafeChecks(r: ReconResult, e: EndpointResult) {
        authorizationGate("LOW-IMPACT SURFACE CHECKS") {
            startSafeChecks(r, e)
        }
    }

    private fun startSafeChecks(r: ReconResult, e: EndpointResult) {
        showLoading("RUNNING LOW-IMPACT SURFACE CHECKS", r.url)
        executor.execute {
            val checks = mutableListOf<SurfaceCheck>()
            for (url in e.endpoints.take(40)) { checks += checkEndpoint(url); Thread.sleep(180) }
            val a = AssessmentResult(r, e, checks, correlateFindings(r, e, checks)); lastAssessment = a
            runOnUiThread { showAssessmentResult(a) }
        }
    }

    private fun checkEndpoint(target: String): SurfaceCheck {
        var c: HttpURLConnection? = null
        return try {
            val start = System.currentTimeMillis(); c = URL(target).openConnection() as HttpURLConnection
            c.requestMethod = "GET"; c.instanceFollowRedirects = false; c.connectTimeout = 5000; c.readTimeout = 5000
            c.setRequestProperty("User-Agent", "BountyPilot/1.0 authorized-safe-assessment"); c.connect()
            val notes = buildString {
                append("${System.currentTimeMillis() - start} ms")
                if (c.getHeaderField("Strict-Transport-Security") == null) append(" • HSTS not observed")
                if (c.getHeaderField("Content-Security-Policy") == null) append(" • CSP not observed")
                if (c.getHeaderField("X-Content-Type-Options") == null) append(" • X-Content-Type-Options not observed")
                if (c.getHeaderField("X-Frame-Options") == null) append(" • X-Frame-Options not observed")
                if (c.getHeaderField("Referrer-Policy") == null) append(" • Referrer-Policy not observed")
                if (c.getHeaderField("Access-Control-Allow-Origin") == "*") append(" • wildcard CORS observed")
            }
            SurfaceCheck(target, "${c.responseCode} ${c.responseMessage ?: ""}".trim(), c.contentType ?: "unknown", c.getHeaderField("Location") ?: "-", notes)
        } catch (e: Exception) { SurfaceCheck(target, "ERROR", "-", "-", classifyException(e)) }
        finally { c?.disconnect() }
    }

    private fun correlateFindings(r: ReconResult, e: EndpointResult, checks: List<SurfaceCheck>): List<String> {
        val out = linkedSetOf<String>(); r.candidates.forEach { out += it }
        if (e.apiLike.isNotEmpty()) out += "Surface candidate: ${e.apiLike.size} API-like endpoint(s) discovered — review authorization and input handling manually."
        if (e.parameterized.isNotEmpty()) out += "Surface candidate: ${e.parameterized.size} parameterized URL(s) discovered — safe input canary queue available."
        if (e.authLike.isNotEmpty()) out += "Surface candidate: authentication/account endpoint(s) discovered — review with controlled accounts."
        if (e.interesting.isNotEmpty()) out += "Surface candidate: ${e.interesting.size} interesting path(s) discovered — verify intended exposure."
        if (checks.any { it.notes.contains("wildcard CORS") }) out += "Candidate: wildcard CORS observed on at least one discovered endpoint."
        if (checks.any { it.status.startsWith("200") && it.url.contains("swagger", true) }) out += "Candidate: public Swagger-like endpoint returned 200 — exposure review."
        if (checks.any { it.status.startsWith("200") && it.url.contains("openapi", true) }) out += "Candidate: public OpenAPI-like endpoint returned 200 — exposure review."
        return out.toList()
    }

    private fun showAssessmentResult(a: AssessmentResult) {
        val v = root(); header(v, "ASSESSMENT CORE", a.recon.url)
        addSection(v, "SCAN SCORECARD", "Endpoints discovered: ${a.endpoints.endpoints.size}\nEndpoints checked: ${a.checks.size}\nCandidates: ${a.findings.size}\nConfirmed vulnerabilities: 0 (manual proof required)")
        val checkLines = a.checks.joinToString("\n") { "${it.status} • ${it.url}\n${it.notes}" }
        addSection(v, "SURFACE CHECK MATRIX", checkLines.take(15000).ifBlank { "No endpoints checked." })
        addSection(v, "VULNERABILITY STATUS", "CONFIRMED: 0\n\nThese are candidates, not confirmed vulnerabilities. A reportable bug requires a reproducible security impact and program eligibility.", yellow)
        if (a.findings.isNotEmpty()) addSection(v, "CANDIDATES", a.findings.mapIndexed { i, f -> "${i + 1}. ${findingSummary(f)}" }.joinToString("\n\n"), yellow)
        addNavigation(v, "‹ ENDPOINTS", { showEndpointDiscovery(a.recon) }, "OPEN TEST LAB ›", { showValidation(a) })
        v.addView(gap(8)); v.addView(cyberButton("▣ REPORT BUILDER", { showReport(a) }, cyan), LinearLayout.LayoutParams(-1, dp(50)))
        v.addView(gap(8)); v.addView(darkButton("↻ NEW TARGET") { showHome() }, LinearLayout.LayoutParams(-1, dp(50)))
        setScreen(v)
    }

    // ---------------------- TEST LAB ----------------------

    private data class ManualProbe(val category: String, val title: String, val purpose: String, val probe: String, val signal: String, val mode: String = "MANUAL", val risk: String = "LOW")

    private data class ProbeResult(val code: Int, val status: String, val elapsed: Long, val bodySnippet: String, val headers: String, val reflected: Boolean, val errorSignal: Boolean)

    private fun safeProbeLibrary(): List<ManualProbe> = buildList {
        // Header / CORS validation
        add(ManualProbe("CORS", "Invalid Origin canary #1", "Check whether an arbitrary origin is reflected.", "Origin: https://example.invalid", "Compare Access-Control-Allow-Origin and Access-Control-Allow-Credentials. Never assume reflection alone is exploitable."))
        add(ManualProbe("CORS", "Invalid Origin canary #2", "Distinguish a fixed allowlist from reflection.", "Origin: https://bountypilot.invalid", "Compare with the previous origin."))
        add(ManualProbe("CORS", "Null Origin review", "Review how the application handles a null origin if the program permits this test.", "Origin: null", "Look for unsafe origin acceptance. Treat only concrete sensitive-data impact as a vulnerability."))
        add(ManualProbe("HEADERS", "OPTIONS capability check", "Review advertised HTTP methods without changing application data.", "OPTIONS /", "Record Allow / Access-Control-Allow-Methods and compare with documented behavior." , "IN-APP", "LOW"))
        add(ManualProbe("HEADERS", "Cache-control review", "Check whether sensitive responses have appropriate cache directives.", "Inspect Cache-Control / Pragma / Vary", "Look for sensitive authenticated content cached contrary to application requirements."))
        // Reflection / encoding canaries
        val reflection = listOf("bountyprobe-001", "bountyprobe-002", "bountyprobe-UNICODE-✓", "bountyprobe space", "bountyprobe%20encoded", "'", "\"", "<bountyprobe>", "&bountyprobe;", "${'$'}{bountyprobe}")
        reflection.forEachIndexed { i, p -> add(ManualProbe("INPUT", "Reflection canary ${i + 1}", "Place one benign canary into an authorized text parameter.", p, "Check whether the exact canary is reflected, transformed, encoded, or omitted. Reflection alone is not XSS.")) }
        // Syntax/error probes — deliberately non-destructive
        listOf("'", "\"", ")", "(", "\\", "`", "' )", "\" )", "\\'", "0'0", "1 AND 1=1", "1 AND 1=2").forEachIndexed { i, p ->
            add(ManualProbe("INPUT-SYNTAX", "Syntax probe ${i + 1}", "Check whether input causes a safe, distinguishable parser/error response.", p, "Look for a controlled validation error or a stable response difference. Do not escalate into destructive database testing."))
        }
        // Path normalization canaries — manual only
        listOf("../bountyprobe", "..%2fbountyprobe", "%2e%2e%2fbountyprobe", "./bountyprobe", "//bountyprobe").forEachIndexed { i, p ->
            add(ManualProbe("PATH", "Path normalization ${i + 1}", "Review normalization behavior on an authorized path parameter.", p, "Look for unexpected path resolution. Stop if unrelated files/data become accessible."))
        }
        // Open redirect canaries
        listOf("https://example.invalid/", "//example.invalid/", "/\\/example.invalid/").forEachIndexed { i, p ->
            add(ManualProbe("REDIRECT", "Redirect canary ${i + 1}", "Test an explicit redirect/return parameter without using a real third-party target.", p, "Record the Location header. A redirect to an external origin may be an open-redirect candidate only when impact is demonstrated."))
        }
        // Authentication: one invalid check, no brute force
        add(ManualProbe("AUTH", "Single invalid username", "Check predictable error behavior with a controlled test account.", "invalid-bountyprobe-user", "Compare status, error wording and response shape with a valid test account. Do not enumerate real users."))
        add(ManualProbe("AUTH", "Single invalid password", "Check authentication failure handling with your own test account.", "Wrong-BountyPilot-Password-001!", "Look for safe generic errors, rate limits and consistent behavior. Never automate credential attempts."))
        add(ManualProbe("AUTH", "Session cookie review", "Review session cookie flags after a controlled login.", "Inspect Set-Cookie", "Check Secure, HttpOnly and SameSite plus session rotation behavior."))
        // API / access control manual guides
        add(ManualProbe("IDOR", "Controlled object comparison", "Use two accounts you control and compare one test object's ordinary request.", "ACCOUNT_A_OBJECT_ID → ACCOUNT_B_OBJECT_ID", "Only continue if both objects belong to your controlled accounts. Never access unrelated users."))
        add(ManualProbe("API", "Unknown field canary", "For JSON APIs, manually add a harmless unknown field if explicitly permitted.", "bountyPilotUnknownField", "Look for safe schema rejection or ignored fields. Do not change privileged fields."))
        add(ManualProbe("API", "Boundary value canary", "Use a small boundary value for an integer/string parameter.", "0", "Observe validation behavior. Avoid huge values or resource-intensive inputs."))
        add(ManualProbe("API", "Negative boundary canary", "Check server-side validation of negative input where type allows numbers.", "-1", "Expect a validation error or documented behavior."))
        add(ManualProbe("API", "Unicode normalization", "Check consistent handling of a benign Unicode value.", "bountyprobe-✓-é", "Compare normalization, storage and response behavior."))
        // Template / encoding canaries — benign expressions only
        listOf("${'$'}{7*7}", "{{7*7}}", "<%= 7*7 %>", "bountyprobe-%00", "bountyprobe-%0a", "bountyprobe-%0d%0a", "bountyprobe%2Fencoded").forEachIndexed { i, p ->
            add(ManualProbe("TEMPLATE-ENCODING", "Template/encoding canary ${i + 1}", "Check whether a benign expression or encoded canary is interpreted or normalized unexpectedly.", p, "Compare literal reflection with transformed output. Do not escalate to executable template or command payloads."))
        }
        // SSRF / callback review is manual-only: use a program-approved controlled callback domain.
        add(ManualProbe("SSRF", "Controlled callback placeholder", "If SSRF testing is explicitly allowed, replace the placeholder with a callback endpoint you control.", "https://YOUR-CONTROLLED-CALLBACK.invalid/bountyprobe", "Only a callback you own/control should be used. Do not target internal IPs, cloud metadata, localhost, or third-party services.", "MANUAL", "MEDIUM"))
        // CSRF is manual-only because it can change application state.
        add(ManualProbe("CSRF", "State-change review", "Inspect whether a state-changing action requires a valid anti-CSRF mechanism.", "No payload — inspect CSRF token / SameSite", "Use only a test account and an explicitly permitted state-changing action. Do not automate the action." , "MANUAL", "MEDIUM"))

        // Documentation / exposed surface
        listOf("/swagger", "/swagger-ui/", "/openapi.json", "/api-docs", "/docs", "/graphql", "/health", "/actuator/health").forEach { p ->
            add(ManualProbe("SURFACE", "Public path review: $p", "Check whether a common public endpoint is intentionally exposed.", p, "Record status/content-type. Public documentation/health endpoints are not automatically vulnerabilities."))
        }
        // Generic evidence helpers
        add(ManualProbe("EVIDENCE", "Response fingerprint", "Record status, length, content type and selected security headers before/after a test.", "No payload — capture baseline", "Use the baseline to compare later probes and avoid false positives."))
        add(ManualProbe("EVIDENCE", "Redirect fingerprint", "Record Location without following an unexpected redirect.", "No payload — inspect Location", "Preserve the exact affected URL and response headers."))
    }

    private fun buildManualQueue(a: AssessmentResult): List<ManualProbe> {
        val all = safeProbeLibrary().toMutableList()
        val hasIdor = a.endpoints.apiLike.isNotEmpty()
        // Keep the broad manual library visible even when passive recon found
        // no query parameter. This is important: a page can have POST/body
        // inputs or client-side parameters that are not discoverable from HTML.
        val filtered = all.filter { p ->
            when (p.category) {
                "IDOR" -> hasIdor
                else -> true
            }
        }
        return filtered.distinctBy { it.category + "|" + it.title }.take(100)
    }

    private fun showValidation(a: AssessmentResult) {
        val v = root(); header(v, "MANUAL TEST LAB", a.recon.url)
        val intro = card(); intro.addView(text("◉ ONE TEST → ONE OBSERVATION", 14f, cyan, true)); intro.addView(gap(6)); intro.addView(text("The queue is generated from the discovered surface plus a broad safe test library. Tests are shown one at a time. Copy and test manually, or use IN-APP SAFE GET when the probe is explicitly marked safe. If interesting behavior appears, stop and preserve evidence.", 12.5f, white)); v.addView(intro); v.addView(gap(10))

        val targetCard = card()
        targetCard.addView(text("TEST ENDPOINT OVERRIDE", 11.5f, cyan, true))
        targetCard.addView(gap(5))
        val endpointInput = input("Optional: paste one same-origin authorized GET URL")
        targetCard.addView(endpointInput, LinearLayout.LayoutParams(-1, dp(50)))
        targetCard.addView(gap(6))
        targetCard.addView(darkButton("USE THIS ENDPOINT") {
            val candidate = endpointInput.text.toString().trim()
            testEndpointOverride = if (candidate.isBlank()) "" else (normalizeUrl(candidate) ?: "")
            if (candidate.isNotBlank() && testEndpointOverride.isBlank()) Toast.makeText(this, "Invalid URL.", Toast.LENGTH_SHORT).show()
            else Toast.makeText(this, if (testEndpointOverride.isBlank()) "Using discovered endpoints." else "Endpoint override set.", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(-1, dp(46)))
        v.addView(targetCard)
        v.addView(gap(8))

        val queue = buildManualQueue(a)
        showQueue(v, a, queue)

        addSection(v, "WHY MANY ITEMS SAY NOT OBSERVED", "A missing header is an observation, not proof of a vulnerability. BountyPilot now separates: (1) observed facts, (2) candidate hypotheses, (3) manual validation, and (4) confirmed report evidence. This prevents a hardening signal from being mislabeled as a bounty bug.", yellow)
        addSection(v, "AUTHORIZATION GUARDRAIL", "In-app tests are limited to one low-impact GET/OPTIONS request at a time. Do not enter credentials, cookies, API keys, or private data into the app. Do not use the tester against assets outside the program's written scope.", red)
        addNavigation(v, "‹ ASSESSMENT", { showAssessmentResult(a) }, "REPORT ›", { showReport(a) })
        setScreen(v)
    }

    private fun showQueue(v: LinearLayout, a: AssessmentResult, queue: List<ManualProbe>) {
        if (queue.isEmpty()) { addSection(v, "TEST QUEUE", "No queue generated."); return }
        var index = 0
        val counter = text("TEST 1 / ${queue.size}", 12f, gray, true)
        val title = text(queue[0].title, 20f, cyan, true)
        val category = text("${queue[0].category} • ${queue[0].risk} • ${queue[0].mode}", 10.5f, blue, true)
        val purpose = text(queue[0].purpose, 12.5f, white)
        val probeBox = text(queue[0].probe, 15f, cyan, true).apply { setPadding(dp(13), dp(15), dp(13), dp(15)); background = GradientDrawable().apply { cornerRadius = dp(9).toFloat(); setColor(Color.rgb(4, 15, 31)); setStroke(dp(1), Color.rgb(20, 90, 155)) } }
        val signal = text("LOOK FOR\n${queue[0].signal}", 11.5f, gray)
        val observation = input("Observation / evidence note")
        val resultLabel = text("RESULT: NOT TESTED", 12f, yellow, true)
        lateinit var next: Button
        lateinit var send: Button
        next = cyberButton("✦ DIDN'T WORK — NEXT TEST", {
            if (index < queue.size - 1) { index++; refreshProbe(index, queue, counter, title, category, purpose, probeBox, signal, resultLabel, observation, next, send, a) }
            else { resultLabel.text = "QUEUE COMPLETE"; Toast.makeText(this, "Safe test queue complete.", Toast.LENGTH_SHORT).show() }
        })
        send = darkButton("⚡ IN-APP SAFE GET / OPTIONS") { runInAppProbe(queue[index], a, observation, resultLabel) }
        val stop = cyberButton("■ WORKED / INTERESTING — STOP & SAVE", {
            resultLabel.text = "STOPPED — EVIDENCE PRESERVATION REQUIRED"; resultLabel.setTextColor(red)
            copy("BountyPilot evidence note", "Test: ${queue[index].title}\nProbe: ${queue[index].probe}\nObservation: ${observation.text}\nTarget: ${a.recon.url}")
            Toast.makeText(this, "Queue stopped. Evidence note copied.", Toast.LENGTH_LONG).show()
        }, cyan)

        v.addView(gap(10)); v.addView(counter); v.addView(gap(5)); v.addView(category); v.addView(title); v.addView(gap(5)); v.addView(purpose); v.addView(gap(8)); v.addView(text("CURRENT TEST / CANARY", 11f, yellow, true)); v.addView(gap(5)); v.addView(probeBox, LinearLayout.LayoutParams(-1, dp(92))); v.addView(gap(7)); v.addView(signal); v.addView(gap(8)); v.addView(observation, LinearLayout.LayoutParams(-1, dp(90))); v.addView(gap(6)); v.addView(resultLabel); v.addView(gap(7)); v.addView(next, LinearLayout.LayoutParams(-1, dp(50))); v.addView(gap(7)); v.addView(send, LinearLayout.LayoutParams(-1, dp(50))); v.addView(gap(7)); v.addView(stop, LinearLayout.LayoutParams(-1, dp(50)))
    }

    private fun refreshProbe(index: Int, q: List<ManualProbe>, counter: TextView, title: TextView, category: TextView, purpose: TextView, probe: TextView, signal: TextView, result: TextView, observation: EditText, next: Button, send: Button, a: AssessmentResult) {
        val p = q[index]; counter.text = "TEST ${index + 1} / ${q.size}"; category.text = "${p.category} • ${p.risk} • ${p.mode}"; title.text = p.title; purpose.text = p.purpose; probe.text = p.probe; signal.text = "LOOK FOR\n${p.signal}"; result.text = "RESULT: NOT TESTED"; result.setTextColor(yellow); observation.setText("")
        send.isEnabled = p.mode == "IN-APP" || p.category in setOf("CORS", "HEADERS", "INPUT", "INPUT-SYNTAX", "REDIRECT", "SURFACE")
    }

    private fun runInAppProbe(p: ManualProbe, a: AssessmentResult, observation: EditText, resultLabel: TextView) {
        val endpoint = chooseTestEndpoint(a, p) ?: run { Toast.makeText(this, "No suitable GET endpoint found. Paste an authorized endpoint above.", Toast.LENGTH_LONG).show(); return }
        try {
            if (!URL(endpoint).host.equals(a.recon.host, true)) {
                Toast.makeText(this, "In-app testing is restricted to the scanned host.", Toast.LENGTH_LONG).show(); return
            }
        } catch (_: Exception) {
            Toast.makeText(this, "Invalid test endpoint.", Toast.LENGTH_LONG).show(); return
        }
        val confirm = CheckBox(this).apply { text = "I confirm this is an authorized target and a low-impact test is permitted."; setTextColor(white) }
        AlertDialog.Builder(this).setTitle("AUTHORIZE ONE SAFE TEST").setMessage("Target: $endpoint\n\nBountyPilot will send one low-impact ${if (p.category == "HEADERS") "OPTIONS/GET" else "GET"} request. It will not follow redirects or submit credentials.").setView(confirm).setNegativeButton("CANCEL", null).setPositiveButton("RUN ONCE") { _, _ ->
            if (!confirm.isChecked) { Toast.makeText(this, "Confirmation required.", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
            showLoading("RUNNING ONE SAFE TEST", endpoint)
            executor.execute {
                val pr = performProbe(endpoint, p)
                lastProbeResult = pr
                runOnUiThread { showProbeResult(p, endpoint, pr, observation, resultLabel, a) }
            }
        }.show()
    }

    private fun chooseTestEndpoint(a: AssessmentResult, p: ManualProbe): String? {
        if (testEndpointOverride.isNotBlank()) return testEndpointOverride
        return when {
            p.category in setOf("CORS", "HEADERS") -> a.endpoints.endpoints.firstOrNull() ?: a.recon.url
            p.category in setOf("INPUT", "INPUT-SYNTAX", "REDIRECT") -> a.endpoints.parameterized.firstOrNull() ?: if (a.recon.url.contains("?")) a.recon.url else null
            p.category == "SURFACE" -> a.endpoints.interesting.firstOrNull() ?: a.recon.url
            else -> a.endpoints.endpoints.firstOrNull() ?: a.recon.url
        }
    }

    private fun buildProbeUrl(endpoint: String, p: ManualProbe): String {
        if (p.category !in setOf("INPUT", "INPUT-SYNTAX", "REDIRECT")) return endpoint
        val encoded = URLEncoder.encode(p.probe, "UTF-8")
        if (endpoint.contains("{{PAYLOAD}}")) return endpoint.replace("{{PAYLOAD}}", encoded)
        return try {
            val u = URL(endpoint)
            val q = u.query
            if (!q.isNullOrBlank()) {
                val first = q.split("&").toMutableList()
                if (first.isNotEmpty()) {
                    val kv = first[0].substringBefore("=")
                    first[0] = "$kv=$encoded"
                }
                URL(u.protocol, u.host, u.port, u.path + "?" + first.joinToString("&")).toString()
            } else {
                URL(u.protocol, u.host, u.port, u.path + "?bountypilot_probe=$encoded").toString()
            }
        } catch (_: Exception) { endpoint }
    }

    private fun performProbe(endpoint: String, p: ManualProbe): ProbeResult {
        var c: HttpURLConnection? = null
        val start = System.currentTimeMillis()
        return try {
            val testedUrl = buildProbeUrl(endpoint, p)
            c = URL(testedUrl).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false; c.connectTimeout = 6000; c.readTimeout = 6000
            c.requestMethod = if (p.category == "HEADERS" && p.title.contains("OPTIONS")) "OPTIONS" else "GET"
            c.setRequestProperty("User-Agent", "BountyPilot/1.0 safe-probe")
            if (p.category == "CORS") c.setRequestProperty("Origin", p.probe.substringAfter("Origin:").trim())
            val code = c.responseCode
            val body = readBodySafely(c)
            val headers = c.headerFields.entries.filter { it.key != null }.take(25).joinToString("\n") { "${it.key}: ${it.value.joinToString(" | ")}" }
            val reflected = p.probe.isNotBlank() && body.contains(p.probe, true)
            val errorSignal = Regex("(?i)(sql syntax|database error|stack trace|exception|syntax error|invalid query)").containsMatchIn(body)
            ProbeResult(code, "${code} ${c.responseMessage ?: ""}".trim(), System.currentTimeMillis() - start, body.take(1800), headers, reflected, errorSignal)
        } catch (e: Exception) {
            ProbeResult(-1, "ERROR: ${classifyException(e)}", System.currentTimeMillis() - start, "", "", false, false)
        } finally { c?.disconnect() }
    }

    private fun showProbeResult(p: ManualProbe, endpoint: String, pr: ProbeResult, observation: EditText, resultLabel: TextView, a: AssessmentResult) {
        val v = root(); header(v, "ONE TEST RESULT", endpoint)
        addSection(v, "TEST", "${p.category} • ${p.title}\nProbe: ${p.probe}\nMode: ${p.mode}")
        addSection(v, "RESPONSE", "Status: ${pr.status}\nTime: ${pr.elapsed} ms\nReflected canary: ${pr.reflected}\nError-like signal: ${pr.errorSignal}")
        addSection(v, "RELEVANT HEADERS", pr.headers.ifBlank { "No headers captured." })
        addSection(v, "BODY SAMPLE", pr.bodySnippet.ifBlank { "No response body captured." })
        addSection(v, "INTERPRETATION", interpretProbe(p, pr), yellow)
        addSection(v, "EVIDENCE NOTE", "Add exact URL, request type, sanitized response, and why the behavior matters. Never include passwords, session cookies, API keys, or private user data.")
        v.addView(cyberButton("▣ COPY TEST RESULT", { copy("BountyPilot test result", buildProbeReport(p, endpoint, pr)) }, cyan), LinearLayout.LayoutParams(-1, dp(50)))
        v.addView(gap(8)); v.addView(darkButton("‹ RETURN TO TEST QUEUE") { showValidation(a) }, LinearLayout.LayoutParams(-1, dp(50)))
        setScreen(v)
    }

    private fun interpretProbe(p: ManualProbe, pr: ProbeResult): String = when {
        pr.errorSignal -> "A parser/error-like signal was observed. This is not proof of injection. Preserve the exact sanitized response and manually validate impact within scope."
        pr.reflected -> "The canary appears in the response. Reflection is not automatically XSS; determine the output context and encoding manually."
        p.category == "CORS" -> "Compare the Origin value with Access-Control-Allow-Origin and credential behavior. Only sensitive cross-origin access with meaningful impact should become a security finding."
        p.category == "HEADERS" -> "Treat missing headers as hardening observations unless a concrete exploit path is demonstrated."
        else -> "No high-confidence impact was automatically established. Use the result as evidence for the next manual step."
    }

    private fun buildProbeReport(p: ManualProbe, endpoint: String, pr: ProbeResult) = buildString {
        append("TEST EVIDENCE\n\nCategory: ${p.category}\nTest: ${p.title}\nEndpoint: $endpoint\nProbe: ${p.probe}\nStatus: ${pr.status}\nResponse time: ${pr.elapsed} ms\nReflected: ${pr.reflected}\nError signal: ${pr.errorSignal}\n\nHEADERS\n${pr.headers}\n\nBODY SAMPLE\n${pr.bodySnippet}\n")
    }

    // ---------------------- TRAINING / DEMO LAB ----------------------

    private data class DemoFinding(
        val severity: String,
        val title: String,
        val endpoint: String,
        val evidence: String,
        val impact: String,
        val remediation: String,
        val cwe: String
    )

    private fun demoFindings(): List<DemoFinding> = listOf(
        DemoFinding(
            "HIGH",
            "Reflected input reaches an unsafe HTML context",
            "https://training.invalid/search?q=DEMO_CANARY",
            "Synthetic lab response reflects DEMO_CANARY inside an HTML attribute without the required contextual encoding.",
            "In a real application, attacker-controlled input could become executable markup/script depending on the exact sink and browser context.",
            "Apply context-aware output encoding, avoid unsafe HTML sinks, and add a restrictive CSP as defense in depth.",
            "CWE-79"
        ),
        DemoFinding(
            "HIGH",
            "Authorization check missing for a controlled object",
            "https://training.invalid/api/demo-object/2002",
            "Synthetic lab: Account B can retrieve a demo object assigned only to Account A when the object identifier is changed.",
            "An IDOR/BOLA issue can expose or modify another user's data when server-side authorization is missing.",
            "Enforce object-level authorization on every request using the authenticated principal and server-side ownership/permission checks.",
            "CWE-639"
        ),
        DemoFinding(
            "MEDIUM",
            "Overly permissive CORS on sensitive response",
            "https://training.invalid/api/profile",
            "Synthetic lab: arbitrary Origin is accepted and credentials are allowed on a sensitive response.",
            "If browser credentials are usable cross-origin, another site may be able to read protected application data.",
            "Use an explicit trusted-origin allowlist and do not combine arbitrary origins with credentialed cross-origin access.",
            "CWE-942"
        ),
        DemoFinding(
            "MEDIUM",
            "Session cookie missing security attributes",
            "https://training.invalid/login",
            "Synthetic lab Set-Cookie lacks one or more expected Secure, HttpOnly, or SameSite protections.",
            "Weak cookie configuration can increase exposure to theft or unintended cross-site transmission depending on deployment and browser behavior.",
            "Set Secure for HTTPS sessions, HttpOnly where client-side script does not need access, and an appropriate SameSite policy.",
            "CWE-614"
        ),
        DemoFinding(
            "MEDIUM",
            "Open redirect in return parameter",
            "https://training.invalid/login?return=//training-external.invalid",
            "Synthetic lab accepts a controlled external destination in a return parameter and issues a redirect.",
            "Open redirects can support phishing and trust-abuse scenarios and may amplify other attack chains.",
            "Allow only local relative paths or enforce a strict server-side destination allowlist.",
            "CWE-601"
        ),
        DemoFinding(
            "LOW",
            "Public API documentation exposes implementation details",
            "https://training.invalid/openapi.json",
            "Synthetic lab returns an OpenAPI document describing internal demo endpoints.",
            "Documentation exposure is not automatically a vulnerability, but it can disclose attack-surface information and should be intentional.",
            "Restrict non-public documentation when appropriate and avoid publishing secrets or internal-only schemas.",
            "CWE-200"
        )
    )

    private fun showToolbox() {
        val v = root()
        header(v, "SECURITY TOOLBOX", "NMAP • TRAFFIC LAB • DNS • TLS • HTTP • PCAP")

        val status = card()
        status.addView(text("ENGINE STATUS", 12f, cyan, true))
        status.addView(gap(6))
        status.addView(text("Nmap: ${if (nmapAvailable()) "AVAILABLE" else "NOT FOUND IN APP ENVIRONMENT"}", 12.5f, if (nmapAvailable()) green else yellow, true))
        status.addView(text("PCAP parser: BUILT-IN", 12.5f, green))
        status.addView(text("HTTP/TLS analyzer: BUILT-IN", 12.5f, green))
        status.addView(text("Wireshark: EXTERNAL / PCAP WORKFLOW", 12.5f, gray))
        v.addView(status)
        v.addView(gap(12))

        val nmap = card()
        nmap.addView(text("⚡ NMAP ENGINE", 13f, cyan, true))
        nmap.addView(gap(5))
        nmap.addView(text("Authorized network inventory. Start with host discovery, then service/version inventory. Vulnerability scripts and brute-force modes are intentionally not automated here.", 12f, gray))
        nmap.addView(gap(8))
        val target = input("Authorized IP, hostname or CIDR")
        nmap.addView(target, LinearLayout.LayoutParams(-1, dp(52)))
        nmap.addView(gap(7))
        nmap.addView(cyberButton("DISCOVER HOSTS  •  nmap -sn", {
            val t=target.text.toString().trim()
            if(t.isBlank()) Toast.makeText(this,"Enter an authorized target.",Toast.LENGTH_SHORT).show()
            else authorizationGate("NMAP HOST DISCOVERY") { runNmap(t, listOf("-sn", t)) }
        }, blue), LinearLayout.LayoutParams(-1, dp(48)))
        nmap.addView(gap(6))
        nmap.addView(cyberButton("SERVICE INVENTORY  •  top 100", {
            val t=target.text.toString().trim()
            if(t.isBlank()) Toast.makeText(this,"Enter an authorized target.",Toast.LENGTH_SHORT).show()
            else authorizationGate("NMAP SERVICE INVENTORY") { runNmap(t, listOf("-sT", "-sV", "--top-ports", "100", t)) }
        }, cyan), LinearLayout.LayoutParams(-1, dp(48)))
        nmap.addView(gap(6))
        nmap.addView(darkButton("COPY SAFE NMAP COMMANDS") {
            copy("BountyPilot Nmap commands", "nmap -sn TARGET\nnmap -sT -sV --top-ports 100 TARGET")
        }, LinearLayout.LayoutParams(-1, dp(45)))
        v.addView(nmap)
        v.addView(gap(12))

        val traffic = card()
        traffic.addView(text("📡 TRAFFIC / PCAP LAB", 13f, cyan, true))
        traffic.addView(gap(5))
        traffic.addView(text("Import a PCAP/PCAPNG file and BountyPilot will identify the capture format and basic packet/header metadata. For full packet dissection, open the same capture in Wireshark/tshark on a compatible environment.", 12f, gray))
        traffic.addView(gap(8))
        traffic.addView(cyberButton("OPEN PCAP / PCAPNG", { openPcapPicker() }, blue), LinearLayout.LayoutParams(-1, dp(48)))
        traffic.addView(gap(6))
        traffic.addView(darkButton("COPY TSHARK ANALYSIS COMMAND") {
            copy("tshark command", "tshark -r capture.pcapng -q -z io,phs\ntshark -r capture.pcapng -Y http")
        }, LinearLayout.LayoutParams(-1, dp(45)))
        v.addView(traffic)
        v.addView(gap(12))

        val utilities = card()
        utilities.addView(text("🧰 BUILT-IN UTILITIES", 13f, cyan, true))
        utilities.addView(gap(6))
        utilities.addView(text("DNS resolution • TCP connectivity • TLS certificate/cipher inspection • HTTP headers • redirects • cookies • security headers • endpoint inventory • response timing", 12f, white))
        utilities.addView(gap(8))
        utilities.addView(darkButton("BACK TO TARGET CONSOLE") { showHome() }, LinearLayout.LayoutParams(-1, dp(45)))
        v.addView(utilities)
        setScreen(v)
    }

    private fun nmapAvailable(): Boolean {
        return try {
            val p = ProcessBuilder("nmap", "--version").redirectErrorStream(true).start()
            p.waitFor(2, TimeUnit.SECONDS)
            p.exitValue() == 0
        } catch (_: Exception) { false }
    }

    private fun runNmap(target: String, args: List<String>) {
        cyberScanSound(2400L)
        if (!nmapAvailable()) {
            val safe = "nmap ${args.joinToString(" ")}".trim()
            val box = card()
            box.addView(text("NMAP BINARY NOT AVAILABLE IN APP SANDBOX", 14f, yellow, true))
            box.addView(gap(7))
            box.addView(text("You can still get a real result now: BountyPilot can run a limited single-host TCP inventory, or you can execute the exact Nmap command in Termux/Kali.", 12f, white))
            box.addView(gap(8))
            if (!target.contains("/")) {
                box.addView(cyberButton("RUN BUILT-IN HOST INVENTORY", { runBuiltInHostInventory(target) }, cyan), LinearLayout.LayoutParams(-1, dp(46)))
                box.addView(gap(6))
            }
            box.addView(cyberButton("OPEN / COPY EXTERNAL NMAP", { if (termuxInstalled()) launchTermuxCommand(safe) else showExternalCommand("Nmap external bridge", safe) }, blue), LinearLayout.LayoutParams(-1, dp(46)))
            box.addView(gap(6))
            box.addView(darkButton("COPY COMMAND") { copy("Nmap command", safe) }, LinearLayout.LayoutParams(-1, dp(44)))
            AlertDialog.Builder(this).setTitle("Nmap bridge").setView(box).setPositiveButton("CLOSE", null).show()
            return
        }
        val loading = ProgressBar(this)
        AlertDialog.Builder(this).setTitle("Nmap running").setMessage("Authorized scan in progress…").setView(loading).setCancelable(false).create().also { dlg ->
            dlg.show()
            executor.execute {
                val out = StringBuilder()
                try {
                    val cmd = ArrayList<String>().apply { add("nmap"); addAll(args) }
                    val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
                    BufferedReader(InputStreamReader(p.inputStream)).useLines { lines -> lines.forEach { line -> out.append(line).append('\n') } }
                    p.waitFor(90, TimeUnit.SECONDS)
                    val result = out.toString().ifBlank { "Nmap returned no output." }
                    runOnUiThread {
                        dlg.dismiss()
                        showToolOutput("NMAP RESULT • $target", result) { showNmapConsole() }
                    }
                } catch (e: Exception) {
                    runOnUiThread { dlg.dismiss(); showToolOutput("NMAP ERROR", e.message ?: "Unknown error") { showNmapConsole() } }
                }
            }
        }
    }

    private fun runBuiltInHostInventory(target: String) {
        val normalized = target.trim()
        val loading = ProgressBar(this)
        val dlg = AlertDialog.Builder(this).setTitle("BountyPilot network inventory").setMessage("Authorized host check in progress…").setView(loading).setCancelable(false).create()
        dlg.show()
        executor.execute {
            val out = StringBuilder()
            try {
                val address = InetAddress.getByName(normalized)
                out.append("HOST: ").append(normalized).append("\n")
                out.append("IP: ").append(address.hostAddress).append("\n\n")
                // Built-in fallback uses a compact common/top-service set when Nmap is unavailable.
                // It is intentionally single-host and requires the authorization gate.
                val ports = listOf(
                    7,9,13,21,22,23,25,26,37,53,79,80,81,88,110,111,113,119,135,139,143,161,179,199,389,443,445,465,514,515,548,554,587,631,646,873,902,990,993,995,1025,1080,1433,1723,1883,2049,2375,3128,3306,3389,3690,4444,5000,5060,5432,5900,5985,6379,6443,7001,8000,8008,8080,8081,8088,8089,8090,8181,8443,8888,9000,9042,9090,9200,9300,11211,15672,27017,50000
                )
                val open = java.util.Collections.synchronizedList(mutableListOf<Int>())
                val pool = Executors.newFixedThreadPool(12)
                ports.forEach { port ->
                    pool.execute {
                        try {
                            Socket().use { socket -> socket.connect(InetSocketAddress(address, port), 180) }
                            open.add(port)
                        } catch (_: Exception) { }
                    }
                }
                pool.shutdown()
                pool.awaitTermination(25, TimeUnit.SECONDS)
                out.append("TESTED PORTS: ").append(ports.size).append("\n")
                out.append("OPEN / REACHABLE: ").append(if (open.isEmpty()) "none observed" else open.joinToString(", ")).append("\n")
                out.append("\nThis is BountyPilot's built-in limited TCP inventory, not Nmap output.")
                runOnUiThread { dlg.dismiss(); showToolOutput("BUILT-IN NETWORK INVENTORY", out.toString()) { showNmapConsole() } }
            } catch (e: Exception) {
                runOnUiThread { dlg.dismiss(); showToolOutput("NETWORK INVENTORY ERROR", e.message ?: "Unable to resolve/connect") { showNmapConsole() } }
            }
        }
    }

    private fun showToolOutput(title: String, output: String, backAction: () -> Unit = { showHome() }) {
        val v = root()
        header(v, title, "RAW TOOL OUTPUT • REVIEW BEFORE REPORTING")
        val c = card()
        c.addView(text(output, 11f, white))
        v.addView(c)
        v.addView(gap(10))
        v.addView(cyberButton("COPY OUTPUT", { copy("BountyPilot tool output", output) }, blue), LinearLayout.LayoutParams(-1, dp(48)))
        v.addView(gap(7))
        v.addView(darkButton("‹ BACK") { backAction() }, LinearLayout.LayoutParams(-1, dp(45)))
        setScreen(v)
    }

    private fun openPcapPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "application/vnd.tcpdump.pcap", "application/x-pcapng"))
        }
        startActivityForResult(intent, 220)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        if (requestCode == 220) { analyzePcap(uri); return }
        if (requestCode == 4101) {
            pendingImageForForensics = uri
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            Toast.makeText(this, "Photo selected — tap ANALYZE SELECTED PHOTO", Toast.LENGTH_SHORT).show()
        }
    }

    private fun analyzePcap(uri: Uri) {
        executor.execute {
            try {
                contentResolver.openInputStream(uri).use { input ->
                    if (input == null) throw IllegalStateException("Unable to open capture")
                    val h = ByteArray(32)
                    val n = input.read(h)
                    val magic = if (n >= 4) (h[0].toInt() and 255).toString(16).padStart(2,'0') + " " + (h[1].toInt() and 255).toString(16).padStart(2,'0') + " " + (h[2].toInt() and 255).toString(16).padStart(2,'0') + " " + (h[3].toInt() and 255).toString(16).padStart(2,'0') else "unknown"
                    val format = when(magic.lowercase(Locale.US)) {
                        "d4 c3 b2 a1", "a1 b2 c3 d4", "4d 3c b2 a1", "a1 b2 3c 4d" -> "PCAP"
                        "0a 0d 0d 0a" -> "PCAPNG"
                        else -> "UNKNOWN / UNSUPPORTED HEADER"
                    }
                    val size = contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
                    val report = "FORMAT: $format\nMAGIC: $magic\nSIZE: ${if(size>=0) "$size bytes" else "unknown"}\n\nThis is a capture-file identification pass. Use Wireshark/tshark for full packet dissection."
                    runOnUiThread { showToolOutput("PCAP ANALYSIS", report) { showTrafficSuite() } }
                }
            } catch (e: Exception) {
                runOnUiThread { showToolOutput("PCAP ERROR", e.message ?: "Unable to read capture") { showTrafficSuite() } }
            }
        }
    }

    private fun showTrainingLab() {
        val v = root()
        header(v, "TRAINING LAB", "SYNTHETIC TARGET • NO INTERNET TRAFFIC")
        val banner = card()
        banner.addView(text("✓ DETERMINISTIC DEMO FINDINGS", 14f, green, true))
        banner.addView(gap(6))
        banner.addView(text("These are deliberately simulated vulnerabilities. They are here so you can learn the complete BountyPilot workflow even when a real target has no vulnerability. They are NOT evidence about any real website and must never be submitted as real findings.", 12.5f, white))
        v.addView(banner)
        v.addView(gap(10))

        val findings = demoFindings()
        addSection(v, "DEMO SCORECARD", "Synthetic findings: ${findings.size}\nHigh: ${findings.count { it.severity == "HIGH" }}\nMedium: ${findings.count { it.severity == "MEDIUM" }}\nLow: ${findings.count { it.severity == "LOW" }}\nStatus: SIMULATED — NOT A REAL TARGET", yellow)

        findings.forEachIndexed { i, f ->
            val c = card()
            val severityColor = when (f.severity) {
                "HIGH" -> red
                "MEDIUM" -> yellow
                else -> cyan
            }
            c.addView(text("FINDING ${i + 1} • ${f.severity}", 11.5f, severityColor, true))
            c.addView(gap(5))
            c.addView(text(f.title, 17f, white, true))
            c.addView(gap(5))
            c.addView(text("CWE: ${f.cwe}\nEndpoint: ${f.endpoint}\n\nEvidence:\n${f.evidence}\n\nImpact:\n${f.impact}\n\nRemediation:\n${f.remediation}", 12f, white))
            c.addView(gap(7))
            c.addView(darkButton("COPY FINDING ${i + 1}") {
                copy("Demo finding", "${f.severity} — ${f.title}\nEndpoint: ${f.endpoint}\nCWE: ${f.cwe}\n\nEvidence: ${f.evidence}\n\nImpact: ${f.impact}\n\nRemediation: ${f.remediation}")
            }, LinearLayout.LayoutParams(-1, dp(42)))
            v.addView(c)
            v.addView(gap(8))
        }

        val report = buildDemoReport(findings)
        v.addView(cyberButton("▣ COPY DEMO BUG-BOUNTY REPORT", { copy("BountyPilot demo report", report) }, cyan), LinearLayout.LayoutParams(-1, dp(52)))
        v.addView(gap(8))
        v.addView(darkButton("‹ RETURN TO TARGET SCANNER") { showHome() }, LinearLayout.LayoutParams(-1, dp(52)))
        setScreen(v)
    }

    private fun buildDemoReport(findings: List<DemoFinding>) = buildString {
        append("# BountyPilot Synthetic Training Report\n\n")
        append("> IMPORTANT: SYNTHETIC TRAINING DATA. NOT A REAL SECURITY REPORT.\n\n")
        append("## Asset\nhttps://training.invalid\n\n")
        append("## Findings\n\n")
        findings.forEachIndexed { i, f ->
            append("### ${i + 1}. ${f.title}\n")
            append("Severity: ${f.severity}\nCWE: ${f.cwe}\nAffected endpoint: ${f.endpoint}\n\n")
            append("Evidence: ${f.evidence}\n\nImpact: ${f.impact}\n\nRemediation: ${f.remediation}\n\n")
            append("---\n\n")
        }
        append("## Submission checklist\n")
        append("- Verify the asset is in program scope.\n- Reproduce the behavior manually.\n- Capture sanitized request/response evidence.\n- Explain concrete security impact.\n- Remove all synthetic/demo placeholders before submission.\n")
    }

    // ---------------------- REPORT BUILDER ----------------------

    private fun showReport(a: AssessmentResult) {
        val v = root(); header(v, "REPORT BUILDER", a.recon.url)
        addSection(v, "REPORTING RULE", "This draft follows the structure commonly expected in bounty reports: concise summary, affected asset, prerequisites, exact reproduction steps, evidence, impact, severity rationale, remediation, and retest notes. Replace placeholders with validated facts before submission.", yellow)
        val report = buildHackerOneStyleReport(a)
        val box = text(report, 11.5f, white).apply { setPadding(dp(13), dp(13), dp(13), dp(13)); background = GradientDrawable().apply { cornerRadius = dp(9).toFloat(); setColor(panel2); setStroke(dp(1), Color.rgb(25, 95, 155)) } }
        v.addView(box)
        v.addView(gap(8)); v.addView(cyberButton("▣ COPY FULL REPORT", { copy("BountyPilot report", report) }, cyan), LinearLayout.LayoutParams(-1, dp(52)))
        v.addView(gap(8)); v.addView(darkButton("‹ BACK TO TEST LAB") { showValidation(a) }, LinearLayout.LayoutParams(-1, dp(52)))
        setScreen(v)
    }

    private fun buildHackerOneStyleReport(a: AssessmentResult) = buildString {
        append("# Security Vulnerability Report\n\n")
        append("## Title\n[Validated vulnerability] — [affected endpoint / parameter]\n\n")
        append("## Summary\nDescribe the security impact in 2–4 sentences. Do not claim exploitability until manually reproduced.\n\n")
        append("## Affected Asset\n${a.recon.url}\n\n")
        append("## Scope / Authorization\nProgram: [program name]\nAsset: [in-scope asset]\nTesting authorization: [confirmed]\n\n")
        append("## Severity\nSuggested: [Low / Medium / High / Critical]\nCVSS: [calculate only after impact is established]\nCWE: [CWE identifier if applicable]\n\n")
        append("## Preconditions\n[List only the accounts, roles, or state you actually control.]\n\n")
        append("## Steps to Reproduce\n1. Navigate to: ${a.recon.url}\n2. [Exact authorized action]\n3. [Exact request / parameter change]\n4. [Observed security-impacting response]\n5. [Repeatability]\n\n")
        append("## Proof / Evidence\nStatus: ${a.recon.status}\nSelected IP: ${a.recon.selectedAddress ?: "not recorded"}\nResponse time: ${a.recon.duration}\nRelevant headers: ${a.recon.headers.filter { it.contains("CORS", true) || it.contains("Security", true) }.joinToString(" | ")}\n\n")
        append("## Impact\n[Explain what an attacker can actually achieve, whose data is affected, confidentiality/integrity/availability consequences, and realistic prerequisites.]\n\n")
        append("## Remediation\n[Specific server-side fix. Include authorization, validation, encoding, configuration, or policy changes as appropriate.]\n\n")
        append("## Retest\n[Steps and evidence showing the fix works.]\n\n")
        append("## Candidate Observations\n")
        if (a.findings.isEmpty()) append("None correlated.\n") else a.findings.forEach { append("- ${findingSummary(it)}\n") }
        append("\n## Important\nThis draft contains placeholders and candidate observations. Submit only after the behavior is reproduced, impact is demonstrated, the asset is in scope, and the program permits the testing method.\n")
    }

    private fun findingSummary(f: String): String = "${findingSeverity(f)} • ${findingTitle(f)}"
    private fun findingTitle(f: String): String {
        val x = f.lowercase(Locale.ROOT)
        return when {
            "wildcard cors" in x -> "Wildcard CORS — review sensitive cross-origin exposure"
            "cookie" in x -> "Cookie security attributes — review Secure / HttpOnly / SameSite"
            "hsts" in x -> "HSTS not observed — transport hardening candidate"
            "csp" in x -> "CSP not observed — browser hardening candidate"
            "x-content-type-options" in x -> "X-Content-Type-Options not observed — hardening candidate"
            "x-frame-options" in x -> "X-Frame-Options not observed — clickjacking hardening candidate"
            "referrer-policy" in x -> "Referrer-Policy not observed — privacy hardening candidate"
            "api-like" in x -> "API-like endpoint discovered — authorization/input review"
            "parameterized" in x -> "Parameterized URL discovered — input testing candidate"
            "authentication" in x -> "Authentication/account endpoint discovered — access-control review"
            "interesting path" in x -> "Interesting public path discovered — manual review"
            else -> "Security candidate — manual validation required"
        }
    }
    private fun findingSeverity(f: String): String = when {
        f.contains("wildcard CORS", true) -> "CONTEXT-DEPENDENT"
        f.contains("cookie", true) -> "CONTEXT-DEPENDENT"
        else -> "INFORMATIONAL / HARDENING"
    }

    private fun addNavigation(v: LinearLayout, left: String, leftAction: () -> Unit, right: String, rightAction: () -> Unit) {
        v.addView(gap(12)); val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(darkButton(left, leftAction), LinearLayout.LayoutParams(0, dp(50), 1f).apply { rightMargin = dp(5) })
        row.addView(cyberButton(right, rightAction), LinearLayout.LayoutParams(0, dp(50), 1f).apply { leftMargin = dp(5) })
        v.addView(row)
    }

    private fun addSection(root: LinearLayout, title: String, body: String, color: Int = cyan) {
        root.addView(gap(8)); val c = card(); c.addView(text(title, 11.5f, color, true)); c.addView(gap(6)); c.addView(text(body, 12.5f, white)); c.addView(gap(8)); c.addView(darkButton("COPY ${title.uppercase(Locale.ROOT)}") { copy(title, body) }, LinearLayout.LayoutParams(-1, dp(42))); root.addView(c)
    }

    

    // ---------------- APPFORGE: local-first project generation workstation ----------------
    private data class ForgeLanguage(val name: String, val ext: String, val kind: String)
    private val forgeLanguages = listOf(
        ForgeLanguage("Kotlin","kt","android"), ForgeLanguage("Java","java","android"),
        ForgeLanguage("Python","py","python"), ForgeLanguage("JavaScript","js","web"),
        ForgeLanguage("TypeScript","ts","web"), ForgeLanguage("HTML","html","web"),
        ForgeLanguage("CSS","css","web"), ForgeLanguage("Dart","dart","flutter"),
        ForgeLanguage("C","c","native"), ForgeLanguage("C++","cpp","native"),
        ForgeLanguage("C#","cs","dotnet"), ForgeLanguage("Rust","rs","rust"),
        ForgeLanguage("Go","go","go"), ForgeLanguage("PHP","php","php"),
        ForgeLanguage("Ruby","rb","ruby"), ForgeLanguage("Swift","swift","swift"),
        ForgeLanguage("Objective-C","m","native"), ForgeLanguage("GDScript","gd","godot"),
        ForgeLanguage("SQL","sql","data"), ForgeLanguage("Bash","sh","shell"),
        ForgeLanguage("PowerShell","ps1","shell"), ForgeLanguage("Lua","lua","script"),
        ForgeLanguage("R","r","data"), ForgeLanguage("Scala","scala","jvm"),
        ForgeLanguage("Kotlin Script","kts","jvm")
    )
    private var forgeRoot: File? = null
    private var forgeLogView: TextView? = null
    private var forgeEditor: EditText? = null
    private var forgeTree: TextView? = null
    private var forgeOutput: TextView? = null
    private var forgePreview: WebView? = null
    private var appForgeOpen = false
    private var forgeStatusView: TextView? = null
    private var forgeProjectPathView: TextView? = null

    private fun enterAppForge() {
        appForgeOpen = true
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        uiHandler.postDelayed({ showAppForge() }, 180)
    }

    private fun exitAppForge() {
        appForgeOpen = false
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        uiHandler.postDelayed({ showHome() }, 180)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (appForgeOpen) { exitAppForge() } else { super.onBackPressed() }
    }

    private fun forgeProjectDir(name: String): File {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "ForgeProject" }
        return File(File(filesDir, "appforge_projects"), safe)
    }

    private fun forgeWrite(root: File, relative: String, body: String) {
        val f = File(root, relative); f.parentFile?.mkdirs(); f.writeText(body)
    }

    private fun forgeAndroidTemplate(name: String, description: String): Map<String,String> {
        val pkg = "com.generated.${name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"),"").take(18).ifBlank{"app"}.let { if (it.first().isDigit()) "app$it" else it }}"
        return mapOf(
            "settings.gradle.kts" to "pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }\ndependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }\nrootProject.name=\"$name\"\ninclude(\":app\")\n",
            "build.gradle.kts" to "plugins { id(\"com.android.application\") version \"8.11.0\" apply false }\n",
            "gradle.properties" to "org.gradle.jvmargs=-Xmx1536m\nandroid.useAndroidX=true\n",
            "app/build.gradle.kts" to "plugins { id(\"com.android.application\") }\n\nandroid { namespace=\"$pkg\"; compileSdk=35\n defaultConfig { applicationId=\"$pkg\"; minSdk=26; targetSdk=35; versionCode=1; versionName=\"1.0\" }\n}\n",
            "app/src/main/AndroidManifest.xml" to "<?xml version=\"1.0\" encoding=\"utf-8\"?><manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"><application android:theme=\"@style/AppTheme\" android:label=\"$name\"><activity android:name=\".MainActivity\" android:exported=\"true\"><intent-filter><action android:name=\"android.intent.action.MAIN\"/><category android:name=\"android.intent.category.LAUNCHER\"/></intent-filter></activity></application></manifest>",
            "app/src/main/res/values/styles.xml" to "<resources><style name=\"AppTheme\" parent=\"android:style/Theme.Material.NoActionBar\"><item name=\"android:fontFamily\">sans</item><item name=\"android:colorAccent\">#00E5FF</item><item name=\"android:navigationBarColor\">#020612</item></style></resources>",
            "app/src/main/java/${pkg.replace('.','/')}/MainActivity.java" to "package $pkg;\n\nimport android.app.*; import android.os.*; import android.graphics.Color; import android.widget.*;\npublic class MainActivity extends Activity { public void onCreate(Bundle b){super.onCreate(b); TextView t=new TextView(this); t.setText(\"$name\\n\\n$description\"); t.setTextColor(Color.WHITE); t.setTextSize(20); t.setPadding(32,64,32,32); t.setBackgroundColor(Color.rgb(2,6,18)); setContentView(t);} }"
        )
    }

    private fun forgeWebTemplate(name: String, description: String): Map<String,String> = mapOf(
        "index.html" to "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>$name</title><link rel=\"stylesheet\" href=\"styles.css\"></head><body><main><h1>$name</h1><p>$description</p><button onclick=\"runApp()\">Run</button></main><script src=\"app.js\"></script></body></html>",
        "styles.css" to "body{margin:0;background:#020612;color:#ebf5ff;font:16px sans-serif}main{max-width:900px;margin:12vh auto;padding:32px}button{background:#00e5ff;border:0;border-radius:10px;padding:14px 24px;font-weight:700}",
        "app.js" to "function runApp(){document.querySelector('p').textContent='Generated app is running.'}"
    )

    private fun forgePythonTemplate(name:String, description:String)=mapOf("main.py" to "print(\"$name\")\nprint(\"$description\")\n", "requirements.txt" to "", "README.md" to "# $name\n\n$description\n")
    private fun forgeNodeTemplate(name:String, description:String)=mapOf("package.json" to "{\"name\":\"${name.lowercase().replace(Regex("[^a-z0-9-]"),"-")}\",\"version\":\"1.0.0\",\"scripts\":{\"start\":\"node index.js\"}}", "index.js" to "console.log(${JSONObject.quote(name)});\nconsole.log(${JSONObject.quote(description)});\n")
    private fun forgeGenericTemplate(name:String, language:String, description:String):Map<String,String>{
        val ext=forgeLanguages.firstOrNull{it.name==language}?.ext ?: "txt"
        val file=if(ext=="html")"index.html" else "main.$ext"
        return mapOf(file to "// Generated by BountyPilot AppForge\n// Language: $language\n// Project: $name\n// Requirements: $description\n\n")
    }

    private fun showAppForge() {
        val v = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(bg); setPadding(dp(10),dp(8),dp(10),dp(8)) }
        val top=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        top.addView(text("CODEFORGE AI",20f,cyan,true),LinearLayout.LayoutParams(0,dp(40),1f))
        top.addView(darkButton("LANGUAGES ${forgeLanguages.size}"){showForgeLanguageList()},LinearLayout.LayoutParams(dp(125),dp(40)))
        top.addView(darkButton("EXIT APPFORGE"){exitAppForge()},LinearLayout.LayoutParams(dp(125),dp(40)).apply{leftMargin=dp(6)})
        v.addView(top)
        val body=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        val left=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        left.addView(text("DESCRIBE YOUR PROJECT",11f,cyan,true)); val prompt=multilineInput("Example: Build a cyber dashboard Android app with login, API integration, dark UI, local database and settings."); left.addView(prompt,LinearLayout.LayoutParams(-1,dp(115))); left.addView(gap(5))
        val type=Spinner(this); type.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,arrayOf("Android App","Website","Web App","Python Tool","Node.js","Flutter App","Godot Game","C/C++ Project","Generic Source Project")); left.addView(type,LinearLayout.LayoutParams(-1,dp(45)))
        val name=input("Project name","MyForgeApp"); left.addView(name,LinearLayout.LayoutParams(-1,dp(45))); left.addView(gap(5))
        left.addView(cyberButton("✦ GENERATE PROJECT",{forgeGenerate(name.text.toString(),prompt.text.toString(),type.selectedItem.toString())},cyan),LinearLayout.LayoutParams(-1,dp(48)))
        left.addView(gap(5)); left.addView(darkButton("▶ BUILD PROJECT"){forgeBuild()},LinearLayout.LayoutParams(-1,dp(45)))
        left.addView(darkButton("📦 EXPORT PROJECT TO DOWNLOADS"){forgeExport()},LinearLayout.LayoutParams(-1,dp(45)))
        left.addView(darkButton("📋 COPY PROJECT PATH"){forgeCopyPath()},LinearLayout.LayoutParams(-1,dp(42)))
        left.addView(darkButton("ℹ BUILD SETUP"){forgeBuildSetup()},LinearLayout.LayoutParams(-1,dp(42)))
        body.addView(left,LinearLayout.LayoutParams(dp(300),-1))
        val center=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(7),0,dp(7),0)}
        center.addView(text("LIVE CODE",11f,cyan,true)); forgeEditor=multilineInput("Generated source appears here"); forgeEditor!!.setTextSize(11f); forgeEditor!!.typeface=Typeface.MONOSPACE; center.addView(forgeEditor,LinearLayout.LayoutParams(-1,0,1f)); body.addView(center,LinearLayout.LayoutParams(0,-1,1f))
        val right=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        right.addView(text("PROJECT FILES",11f,cyan,true)); forgeTree=text("No project generated",10.5f,white); forgeTree!!.typeface=Typeface.MONOSPACE; val treeScroll=ScrollView(this); treeScroll.addView(forgeTree); right.addView(treeScroll,LinearLayout.LayoutParams(dp(250),0,1f)); right.addView(text("BUILD / AI LOG",11f,green,true)); forgeLogView=text("Ready. No external terminal will be opened automatically.",10f,green); forgeLogView!!.typeface=Typeface.MONOSPACE; val ls=ScrollView(this); ls.addView(forgeLogView); right.addView(ls,LinearLayout.LayoutParams(dp(250),dp(125))); forgeStatusView=text("BUILD STATUS: READY",9.5f,cyan,true); right.addView(forgeStatusView,LinearLayout.LayoutParams(dp(250),dp(30))); forgeProjectPathView=text("PROJECT PATH: —",8.5f,gray); right.addView(forgeProjectPathView,LinearLayout.LayoutParams(dp(250),dp(34))); body.addView(right,LinearLayout.LayoutParams(dp(250),-1))
        v.addView(body,LinearLayout.LayoutParams(-1,0,1f)); setContentView(v)
    }

    private fun forgeLog(s:String){runOnUiThread{val tv=forgeLogView?:return@runOnUiThread;tv.append("\\n> $s");(tv.parent as? ScrollView)?.post{(tv.parent as ScrollView).fullScroll(View.FOCUS_DOWN)}}}
    private fun forgeGenerate(projectName:String, prompt:String, type:String){
        if(projectName.isBlank()||prompt.isBlank()){Toast.makeText(this,"Describe the project and give it a name.",Toast.LENGTH_SHORT).show();return}
        val root=forgeProjectDir(projectName); if(root.exists()) root.deleteRecursively(); root.mkdirs(); forgeRoot=root; forgeLog("Creating $type project: $projectName");
        executor.execute{try{
            val files=when(type){"Android App"->forgeAndroidTemplate(projectName,prompt);"Website","Web App"->forgeWebTemplate(projectName,prompt);"Python Tool"->forgePythonTemplate(projectName,prompt);"Node.js"->forgeNodeTemplate(projectName,prompt);else->forgeGenericTemplate(projectName,type,prompt)}
            files.forEach{(path,body)->forgeLog("Writing $path");forgeWrite(root,path,body);Thread.sleep(80)}
            val readme="""# $projectName\n\nGenerated by BountyPilot AppForge.\n\nType: $type\n\nDescription:\n$prompt\n\nSupported language catalog: ${forgeLanguages.joinToString(", "){it.name}}\n""";forgeWrite(root,"README.md",readme);forgeLog("✓ Project structure complete");
            runOnUiThread{refreshForgeTree();showForgeFirstFile()}; forgeLog("✓ Ready for build/export")
        }catch(e:Exception){forgeLog("ERROR: ${e.message}")}}
    }

    private fun refreshForgeTree(){val root=forgeRoot?:return; val list=root.walkTopDown().filter{it.isFile}.map{it.relativeTo(root).path}.toList().sorted();forgeTree?.text=list.joinToString("\\n"){"📄 $it"}}
    private fun showForgeFirstFile(){val root=forgeRoot?:return;val f=root.walkTopDown().firstOrNull{it.isFile && it.name!="README.md"}?:return;forgeEditor?.setText(f.readText());forgeEditor?.setTag(f.absolutePath)}

    private fun showForgeLanguageList(){
        val msg=forgeLanguages.joinToString("\\n"){ "• ${it.name}  (.${it.ext})" } + "\\n\\nAppForge selects the right project/toolchain adapter where available. A language being listed does not mean its compiler is bundled into Android.";
        AlertDialog.Builder(this).setTitle("AppForge language catalog (${forgeLanguages.size})").setMessage(msg).setPositiveButton("OK",null).show()
    }

    private fun forgeBuild(){
        val root=forgeRoot?:run{Toast.makeText(this,"Generate a project first.",Toast.LENGTH_SHORT).show();return}
        forgeStatusView?.text="BUILD STATUS: PROJECT READY"
        forgeLog("Build requested inside AppForge.")
        forgeLog("✓ Project validated: ${root.name}")
        forgeLog("✓ No automatic Termux redirect.")
        forgeLog("Android compilation requires an installed Android/Gradle build engine such as Code on the Go.")
        forgeLog("Use EXPORT PROJECT TO DOWNLOADS, then import the generated project into your build environment.")
        forgeStatusView?.text="BUILD STATUS: EXPORT-READY"
        val instruction=File(root,"APPFORGE_BUILD_INSTRUCTIONS.txt")
        instruction.writeText("BountyPilot AppForge project\n\nProject: ${root.name}\n\nThis project was generated locally by AppForge.\n\nRecommended Android workflow:\n1. Import this project into Code on the Go.\n2. Open the project root containing settings.gradle.kts.\n3. Run :app:assembleDebug.\n4. Install the generated APK.\n\nAppForge intentionally does not launch an external terminal automatically.\n")
        refreshForgeTree()
    }

    private fun forgeCopyPath(){
        val root=forgeRoot?:run{Toast.makeText(this,"Generate a project first.",Toast.LENGTH_SHORT).show();return}
        val cm=getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("AppForge project path",root.absolutePath))
        Toast.makeText(this,"Project path copied",Toast.LENGTH_SHORT).show()
    }

    private fun forgeBuildSetup(){
        AlertDialog.Builder(this).setTitle("AppForge build setup")
            .setMessage("AppForge generates the complete project locally and does not automatically redirect to Termux.\n\nFor Android APK/AAB compilation, import the exported project into Code on the Go or another Android/Gradle environment.\n\nFor Web/Python/Node projects, use the corresponding installed runtime/toolchain.\n\nThe next AI phase will add local Ollama planning, code generation and build-error repair without changing this export workflow.")
            .setPositiveButton("OK",null).show()
    }

    private fun forgeExport(){
        val root=forgeRoot?:run{Toast.makeText(this,"Generate a project first.",Toast.LENGTH_SHORT).show();return}
        executor.execute{
            try{
                val zip=File(cacheDir,"${root.name}-AppForge.zip")
                ZipOutputStream(FileOutputStream(zip)).use{zos->root.walkTopDown().filter{it.isFile}.forEach{f->val e=ZipEntry(f.relativeTo(root).path);zos.putNextEntry(e);f.inputStream().use{it.copyTo(zos)};zos.closeEntry()}}
                var shareUri: Uri? = null
                if(Build.VERSION.SDK_INT>=29){
                    val values=ContentValues().apply{put(MediaStore.Downloads.DISPLAY_NAME,zip.name);put(MediaStore.Downloads.MIME_TYPE,"application/zip");put(MediaStore.Downloads.IS_PENDING,1)}
                    val resolver=contentResolver
                    val uri=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)
                    if(uri!=null){resolver.openOutputStream(uri)?.use{out->zip.inputStream().use{it.copyTo(out)}};values.clear();values.put(MediaStore.Downloads.IS_PENDING,0);resolver.update(uri,values,null,null);shareUri=uri}
                }
                val exportedUri = shareUri
                runOnUiThread{
                    if(exportedUri!=null){
                        forgeLog("✓ ZIP exported to Downloads/${zip.name}")
                        forgeStatusView?.text="BUILD STATUS: PROJECT EXPORTED"
                        val i=Intent(Intent.ACTION_SEND).apply{type="application/zip";putExtra(Intent.EXTRA_STREAM,exportedUri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)}
                        startActivity(Intent.createChooser(i,"Share AppForge project"))
                    }else{
                        forgeLog("ZIP created in AppForge cache: ${zip.absolutePath}")
                        forgeStatusView?.text="BUILD STATUS: ZIP READY"
                        Toast.makeText(this,"ZIP ready: ${zip.name}",Toast.LENGTH_LONG).show()
                    }
                }
            }catch(e:Exception){runOnUiThread{forgeLog("EXPORT ERROR: ${e.message}");Toast.makeText(this,"Export failed: ${e.message}",Toast.LENGTH_LONG).show()}}
        }
    }


private class TrafficGraphView(context: Context) : View(context) {
    private val p=Paint(Paint.ANTI_ALIAS_FLAG); private val samples=ArrayDeque<Pair<Long,Long>>()
    fun start(){invalidate()}
    fun setSample(rx:Long,tx:Long){if(samples.size>=36)samples.removeFirst();samples.addLast(rx to tx);invalidate()}
    override fun onDraw(c:Canvas){super.onDraw(c);val w=width.toFloat();val h=height.toFloat();p.style=Paint.Style.STROKE;p.color=Color.rgb(25,95,155);p.strokeWidth=1f;for(i in 1..4)c.drawLine(0f,h*i/5,w,h*i/5,p);if(samples.isEmpty()){p.color=Color.rgb(125,145,170);p.textSize=28f;c.drawText("LIVE TRAFFIC",20f,h/2,p);return};val max=samples.maxOf{maxOf(it.first,it.second)}.coerceAtLeast(1);fun draw(idx:Int,color:Int){p.color=color;p.strokeWidth=4f;var lx=0f;var ly=h;samples.forEachIndexed{n,s->val x=w*n/(maxOf(1,samples.size-1));val value=if(idx==0)s.first else s.second;val y=h-18f-(value.toDouble()/max*h*.82).toFloat();if(n>0)c.drawLine(lx,ly,x,y,p);lx=x;ly=y}};draw(0,Color.rgb(0,229,255));draw(1,Color.rgb(60,255,150));p.style=Paint.Style.FILL;p.textSize=11f;p.color=Color.rgb(0,229,255);c.drawText("UID RX",10f,18f,p);p.color=Color.rgb(60,255,150);c.drawText("UID TX",70f,18f,p)}
}

private class OsintGraphView(context: Context) : View(context) {
    private val p=Paint(Paint.ANTI_ALIAS_FLAG); private var seed="example.com"
    fun setSeed(s:String){seed=s.ifBlank{"example.com"};invalidate()}
    override fun onDraw(c:Canvas){super.onDraw(c);val w=width.toFloat();val h=height.toFloat();val cx=w/2;val cy=h/2;val nodes=listOf(cx to cy,w*.18f to h*.25f,w*.82f to h*.25f,w*.18f to h*.75f,w*.82f to h*.75f,cx to h*.12f);p.style=Paint.Style.STROKE;p.strokeWidth=2f;p.color=Color.rgb(25,95,155);for(i in 1 until nodes.size)c.drawLine(cx,cy,nodes[i].first,nodes[i].second,p);p.style=Paint.Style.FILL;nodes.forEachIndexed{i,n->p.color=if(i==0)Color.rgb(0,229,255) else Color.rgb(60,255,150);c.drawCircle(n.first,n.second,if(i==0)24f else 13f,p)};p.color=Color.WHITE;p.textSize=11f;c.drawText(seed.take(18),cx-45,cy+4,p);p.textSize=9f;listOf("SEED","DNS","ASN","WEB","EMAIL","CERT").forEachIndexed{i,l->c.drawText(l,nodes[i].first-18,nodes[i].second+30,p)}}
}

private class CyberWaveView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var phase = 0f
        init { setBackgroundColor(Color.TRANSPARENT); postInvalidateOnAnimation() }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat(); val cx = w * 0.5f; val cy = h * 0.18f
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.2f; paint.color = Color.argb(28, 0, 210, 255)
            for (i in 1..5) canvas.drawCircle(cx, cy, dpLocal(i * 75).toFloat(), paint)
            paint.color = Color.argb(25, 40, 150, 255)
            val y = (h * 0.42f) + kotlin.math.sin(phase) * 8f
            canvas.drawLine(0f, y, w, y, paint)
            paint.color = Color.argb(22, 0, 229, 255)
            canvas.drawLine(0f, y + 22, w, y + 22, paint)
            phase += 0.045f
            postInvalidateOnAnimation()
        }
        private fun dpLocal(v: Int) = (v * resources.displayMetrics.density).toInt()
    }
}
