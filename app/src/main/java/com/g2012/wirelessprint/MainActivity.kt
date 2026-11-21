package com.g2012.wirelessprint

import android.Manifest
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private val ACTION_USB_PERMISSION = "com.g2012.wirelessprint.USB_PERMISSION"
    private lateinit var usbManager: UsbManager

    // Navigation
    private lateinit var senderLayout: LinearLayout
    private lateinit var serverLayout: LinearLayout
    private lateinit var tabSenderBtn: Button
    private lateinit var tabServerBtn: Button

    // Sender UI
    private lateinit var connectionBanner: TextView
    private lateinit var ipInput: EditText
    private lateinit var previewImage: ImageView
    private lateinit var previewInfoText: TextView
    private lateinit var prevSheetBtn: Button
    private lateinit var nextSheetBtn: Button
    private lateinit var statusBarText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var printBtn: Button

    // Settings UI
    private lateinit var colorSpinner: Spinner
    private lateinit var paperSpinner: Spinner
    private lateinit var mediaSpinner: Spinner
    private lateinit var qualitySpinner: Spinner
    private lateinit var orientationSpinner: Spinner
    private lateinit var nUpSpinner: Spinner
    private lateinit var scaleSpinner: Spinner
    private lateinit var pageFilterSpinner: Spinner
    private lateinit var customRangeInput: EditText
    private lateinit var copiesInput: EditText
    private lateinit var directUsbCheck: CheckBox

    // Server UI
    private lateinit var serverIpText: TextView
    private lateinit var serverStatusText: TextView
    private lateinit var serverToggleBtn: Button

    // State
    private var currentSource: PrintSource = PrintSource.TestPage
    private var currentSheetIndex = 0
    private var totalSheets = 1
    private var pendingUsbAction: (() -> Unit)? = null

    private val pickPdfLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            currentSource = PrintSource.Pdf(uri, uri.lastPathSegment ?: "Document.pdf")
            currentSheetIndex = 0
            refreshPreview()
        }
    }

    private val pickImagesLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (!uris.isNullOrEmpty()) {
            currentSource = PrintSource.Images(uris)
            currentSheetIndex = 0
            refreshPreview()
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) {
                        pendingUsbAction?.invoke()
                    } else {
                        serverStatusText.text = "USB permission denied."
                        statusBarText.text = "USB permission denied."
                    }
                    pendingUsbAction = null
                }
                PrintBridgeService.ACTION_STATUS -> {
                    val msg = intent.getStringExtra(PrintBridgeService.EXTRA_STATUS) ?: return
                    serverStatusText.text = msg
                    updateServerUi()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }

        buildModernUi()

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(PrintBridgeService.ACTION_STATUS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }

        val prefs = getSharedPreferences("g2012_prefs", Context.MODE_PRIVATE)
        ipInput.setText(prefs.getString("last_ip", ""))

        handleIncomingIntent(intent)
        updateServerUi()
        refreshPreview()
        autoDiscoverSparePhone()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val type = intent.type ?: ""
                if (type == "text/plain") {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                    if (!text.isNullOrBlank()) {
                        currentSource = PrintSource.PlainText(text, "Shared Note")
                        currentSheetIndex = 0
                        showTab(isServer = false)
                        refreshPreview()
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                    if (uri != null) {
                        currentSource = if (type.contains("pdf")) {
                            PrintSource.Pdf(uri, "Shared PDF")
                        } else {
                            PrintSource.Images(listOf(uri))
                        }
                        currentSheetIndex = 0
                        showTab(isServer = false)
                        refreshPreview()
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                if (!uris.isNullOrEmpty()) {
                    currentSource = PrintSource.Images(uris)
                    currentSheetIndex = 0
                    showTab(isServer = false)
                    refreshPreview()
                }
            }
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                showTab(isServer = true)
                requestUsbPermissionAndRun { startBridgeService() }
            }
        }
    }

    private fun makeCard(bgColor: Int = Color.WHITE, strokeColor: Int = Color.parseColor("#E0E6ED")): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 28, 32, 28)
            background = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = 28f
                setStroke(2, strokeColor)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 24) }
        }
    }

    private fun makeRoundedButton(label: String, bgHex: String, textColor: Int = Color.WHITE): Button {
        return Button(this).apply {
            text = label
            setTextColor(textColor)
            isAllCaps = false
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bgHex))
                cornerRadius = 22f
            }
            setPadding(24, 22, 24, 22)
        }
    }

    private fun buildModernUi() {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F4F6F9"))
        }

        // Top App Bar
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1565C0"))
            setPadding(36, 44, 36, 28)
        }
        header.addView(TextView(this).apply {
            text = "G2012 Print Pro"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        header.addView(TextView(this).apply {
            text = "Canon PIXMA G2012 Wireless & OTG Suite"
            textSize = 13f
            setTextColor(Color.parseColor("#BBDEFB"))
            setPadding(0, 4, 0, 20)
        })

        // Segmented Mode Tabs
        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tabSenderBtn = makeRoundedButton("Print (Main Phone)", "#0D47A1").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, 10, 0)
            }
            setOnClickListener { showTab(isServer = false) }
        }
        tabServerBtn = makeRoundedButton("Server (Spare Phone)", "#1E88E5").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(10, 0, 0, 0)
            }
            setOnClickListener { showTab(isServer = true) }
        }
        tabRow.addView(tabSenderBtn)
        tabRow.addView(tabServerBtn)
        header.addView(tabRow)
        outer.addView(header)

        // Scrollable Body
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 36)
        }

        // ==================== SENDER (MAIN PHONE) ====================
        senderLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // 1. Printer Connection Card
        val connCard = makeCard()
        connectionBanner = TextView(this).apply {
            text = "Searching for Spare Phone on Wi-Fi..."
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1565C0"))
            setPadding(0, 0, 0, 12)
        }
        val ipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        ipInput = EditText(this).apply {
            hint = "Spare Phone IP (e.g. 192.168.43.1)"
            inputType = InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val scanBtn = makeRoundedButton("Auto-Find", "#1976D2").apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(12, 0, 0, 0) }
            setOnClickListener { autoDiscoverSparePhone() }
        }
        ipRow.addView(ipInput)
        ipRow.addView(scanBtn)
        directUsbCheck = CheckBox(this).apply {
            text = "Print directly via USB-OTG (no Wi-Fi needed)"
            textSize = 14f
            setPadding(0, 10, 0, 0)
        }
        connCard.addView(connectionBanner)
        connCard.addView(ipRow)
        connCard.addView(directUsbCheck)
        senderLayout.addView(connCard)

        // 2. Source Picker Card (PDF, Photos, Note/Text, Test Page)
        val sourceCard = makeCard()
        sourceCard.addView(sectionTitle("Choose What to Print"))
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 12)
        }
        val btnPdf = makeRoundedButton("PDF Document", "#E53935").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, 8, 0)
            }
            setOnClickListener { pickPdfLauncher.launch(arrayOf("application/pdf")) }
        }
        val btnPhotos = makeRoundedButton("Photos (Multi)", "#00897B").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(8, 0, 0, 0)
            }
            setOnClickListener { pickImagesLauncher.launch(arrayOf("image/*")) }
        }
        row1.addView(btnPdf)
        row1.addView(btnPhotos)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnNote = makeRoundedButton("Type & Print", "#5E35B1").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, 8, 0)
            }
            setOnClickListener { showTextPrintDialog() }
        }
        val btnTest = makeRoundedButton("Test Page", "#455A64").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(8, 0, 0, 0)
            }
            setOnClickListener {
                currentSource = PrintSource.TestPage
                currentSheetIndex = 0
                refreshPreview()
            }
        }
        row2.addView(btnNote)
        row2.addView(btnTest)
        sourceCard.addView(row1)
        sourceCard.addView(row2)
        senderLayout.addView(sourceCard)

        // 3. Live Print Preview Card
        val previewCard = makeCard()
        previewCard.addView(sectionTitle("Live Print Preview"))
        previewImage = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 680
            ).apply { setMargins(0, 12, 0, 12) }
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.parseColor("#ECEFF1"))
            setPadding(16, 16, 16, 16)
        }
        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        prevSheetBtn = makeRoundedButton("< Prev", "#CFD8DC", Color.BLACK).apply {
            setOnClickListener {
                if (currentSheetIndex > 0) {
                    currentSheetIndex--
                    refreshPreview()
                }
            }
        }
        previewInfoText = TextView(this).apply {
            text = "Sheet 1 of 1"
            gravity = Gravity.CENTER
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        nextSheetBtn = makeRoundedButton("Next >", "#CFD8DC", Color.BLACK).apply {
            setOnClickListener {
                if (currentSheetIndex < totalSheets - 1) {
                    currentSheetIndex++
                    refreshPreview()
                }
            }
        }
        navRow.addView(prevSheetBtn)
        navRow.addView(previewInfoText)
        navRow.addView(nextSheetBtn)
        previewCard.addView(previewImage)
        previewCard.addView(navRow)
        senderLayout.addView(previewCard)

        // 4. NokoPrint-Style Print Settings Card
        val settingsCard = makeCard()
        settingsCard.addView(sectionTitle("Print Settings"))

        colorSpinner = addSpinnerRow(settingsCard, "Color Mode", listOf("Black & White (Fast)", "Full Color"))
        paperSpinner = addSpinnerRow(settingsCard, "Paper Size", PaperSize.values().map { it.label })
        mediaSpinner = addSpinnerRow(settingsCard, "Paper Type", listOf("Plain Paper", "Glossy Photo Paper"))
        qualitySpinner = addSpinnerRow(settingsCard, "Print Quality", PrintQuality.values().map { it.label })
        qualitySpinner.setSelection(1) // Normal 200 DPI default
        orientationSpinner = addSpinnerRow(settingsCard, "Orientation", OrientationMode.values().map { it.label })
        nUpSpinner = addSpinnerRow(settingsCard, "Pages Per Sheet", listOf("1 Page per Sheet", "2 Pages per Sheet (2-up)", "4 Pages per Sheet (4-up)"))
        scaleSpinner = addSpinnerRow(settingsCard, "Margins & Scaling", ScaleMode.values().map { it.label })
        pageFilterSpinner = addSpinnerRow(settingsCard, "Pages to Print", PageFilter.values().map { it.label })

        customRangeInput = EditText(this).apply {
            hint = "Custom pages (e.g. 1-3, 5, 7)"
            visibility = View.GONE
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    currentSheetIndex = 0
                    refreshPreview()
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }
        settingsCard.addView(customRangeInput)

        val copiesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 12, 0, 4)
        }
        copiesRow.addView(TextView(this).apply {
            text = "Copies"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        copiesInput = EditText(this).apply {
            setText("1")
            inputType = InputType.TYPE_CLASS_NUMBER
            gravity = Gravity.CENTER
            width = 180
        }
        copiesRow.addView(copiesInput)
        settingsCard.addView(copiesRow)
        senderLayout.addView(settingsCard)

        // 5. Status & Print Action Card
        val actionCard = makeCard()
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
            visibility = View.GONE
        }
        statusBarText = TextView(this).apply {
            text = "Ready to print."
            textSize = 14f
            setTextColor(Color.parseColor("#37474F"))
            setPadding(0, 8, 0, 16)
        }
        printBtn = makeRoundedButton("PRINT NOW", "#1565C0").apply {
            textSize = 18f
            setPadding(24, 30, 24, 30)
            setOnClickListener { startPrintJob() }
        }
        actionCard.addView(progressBar)
        actionCard.addView(statusBarText)
        actionCard.addView(printBtn)
        senderLayout.addView(actionCard)

        // ==================== SERVER (SPARE PHONE) ====================
        serverLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val serverCard = makeCard()
        serverCard.addView(sectionTitle("Spare Phone Wi-Fi Print Server"))
        serverIpText = TextView(this).apply {
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#0D47A1"))
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E3F2FD"))
                cornerRadius = 18f
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 16, 0, 16) }
        }
        serverStatusText = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.parseColor("#37474F"))
            setPadding(0, 8, 0, 20)
            text = "1. Turn ON Mobile Hotspot on this Spare Phone (no SIM/data needed) OR join Wi-Fi.\n" +
                   "2. Turn ON Canon G2012 & plug it in via OTG.\n" +
                   "3. Tap Start Printer Server below."
        }
        serverToggleBtn = makeRoundedButton("Start Printer Server", "#2E7D32").apply {
            textSize = 17f
            setPadding(24, 30, 24, 30)
            setOnClickListener {
                if (PrintBridgeService.isRunning) {
                    stopService(Intent(this@MainActivity, PrintBridgeService::class.java))
                    updateServerUi()
                } else {
                    requestUsbPermissionAndRun { startBridgeService() }
                }
            }
        }
        val refreshIpBtn = makeRoundedButton("Refresh IP Address", "#546E7A").apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 14, 0, 0) }
            setOnClickListener { updateServerUi() }
        }
        serverCard.addView(serverIpText)
        serverCard.addView(serverStatusText)
        serverCard.addView(serverToggleBtn)
        serverCard.addView(refreshIpBtn)
        serverLayout.addView(serverCard)

        body.addView(senderLayout)
        body.addView(serverLayout)
        scroll.addView(body)
        outer.addView(scroll)
        setContentView(outer)

        showTab(isServer = PrintBridgeService.isRunning)
    }

    private fun sectionTitle(title: String): TextView {
        return TextView(this).apply {
            text = title
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#263238"))
            setPadding(0, 0, 0, 8)
        }
    }

    private fun addSpinnerRow(parent: LinearLayout, label: String, items: List<String>): Spinner {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8, 0, 8)
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(Color.parseColor("#455A64"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.9f)
        })
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    if (this@apply == pageFilterSpinner) {
                        customRangeInput.visibility = if (pos == PageFilter.CUSTOM.ordinal) View.VISIBLE else View.GONE
                    }
                    currentSheetIndex = 0
                    refreshPreview()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        row.addView(spinner)
        parent.addView(row)
        return spinner
    }

    private fun readSettings(): PrintSettings {
        val nUp = when (nUpSpinner.selectedItemPosition) {
            1 -> 2
            2 -> 4
            else -> 1
        }
        return PrintSettings(
            isColor = colorSpinner.selectedItemPosition == 1,
            paperSize = PaperSize.values()[paperSpinner.selectedItemPosition],
            mediaType = if (mediaSpinner.selectedItemPosition == 1) "Glossy" else "Plain",
            quality = PrintQuality.values()[qualitySpinner.selectedItemPosition],
            orientation = OrientationMode.values()[orientationSpinner.selectedItemPosition],
            pagesPerSheet = nUp,
            scaleMode = ScaleMode.values()[scaleSpinner.selectedItemPosition],
            pageFilter = PageFilter.values()[pageFilterSpinner.selectedItemPosition],
            customRange = customRangeInput.text.toString(),
            copies = (copiesInput.text.toString().toIntOrNull() ?: 1).coerceIn(1, 99),
            directUsb = directUsbCheck.isChecked
        )
    }

    private fun refreshPreview() {
        val settings = readSettings()
        thread {
            try {
                val sheets = PageComposer.getTotalSheetCount(this, currentSource, settings)
                val safeIndex = if (sheets > 0) currentSheetIndex.coerceIn(0, sheets - 1) else 0
                val bmp = if (sheets > 0) {
                    PageComposer.composeSheet(this, currentSource, settings, safeIndex, dpi = 90, applyBwPreviewFilter = true)
                } else null

                runOnUiThread {
                    totalSheets = sheets
                    currentSheetIndex = safeIndex
                    if (bmp != null) {
                        previewImage.setImageBitmap(bmp)
                        previewInfoText.text = "Sheet ${safeIndex + 1} of $sheets"
                    } else {
                        previewImage.setImageDrawable(null)
                        previewInfoText.text = "No pages selected"
                    }
                    prevSheetBtn.isEnabled = safeIndex > 0
                    nextSheetBtn.isEnabled = safeIndex < sheets - 1
                }
            } catch (e: Exception) {
                runOnUiThread { previewInfoText.text = "Preview error: ${e.message}" }
            }
        }
    }

    private fun showTextPrintDialog() {
        val input = EditText(this).apply {
            hint = "Type or paste text to print..."
            minLines = 5
            gravity = Gravity.TOP or Gravity.START
        }
        AlertDialog.Builder(this)
            .setTitle("Type & Print Note")
            .setView(input)
            .setPositiveButton("Preview") { _, _ ->
                val txt = input.text.toString()
                if (txt.isNotBlank()) {
                    currentSource = PrintSource.PlainText(txt, "Quick Note")
                    currentSheetIndex = 0
                    refreshPreview()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTab(isServer: Boolean) {
        senderLayout.visibility = if (isServer) View.GONE else View.VISIBLE
        serverLayout.visibility = if (isServer) View.VISIBLE else View.GONE
        (tabSenderBtn.background as? GradientDrawable)?.setColor(
            Color.parseColor(if (!isServer) "#0D47A1" else "#1E88E5")
        )
        (tabServerBtn.background as? GradientDrawable)?.setColor(
            Color.parseColor(if (isServer) "#0D47A1" else "#1E88E5")
        )
    }

    private fun autoDiscoverSparePhone() {
        runOnUiThread { connectionBanner.text = "Searching Wi-Fi for Spare Phone..." }
        thread {
            var foundIp: String? = null
            var sock: DatagramSocket? = null
            try {
                sock = DatagramSocket().apply {
                    broadcast = true
                    soTimeout = 3000
                }
                val probe = "G2012_DISCOVER".toByteArray()
                sock.send(DatagramPacket(probe, probe.size, InetAddress.getByName("255.255.255.255"), PrintBridgeService.DISCOVERY_PORT))
                val buf = ByteArray(256)
                val resp = DatagramPacket(buf, buf.size)
                sock.receive(resp)
                if (String(resp.data, 0, resp.length).startsWith("G2012_PRINTER_READY:")) {
                    foundIp = resp.address.hostAddress
                }
            } catch (_: Exception) {
            } finally {
                try { sock?.close() } catch (_: Exception) {}
            }

            runOnUiThread {
                if (foundIp != null) {
                    ipInput.setText(foundIp)
                    getSharedPreferences("g2012_prefs", Context.MODE_PRIVATE)
                        .edit().putString("last_ip", foundIp).apply()
                    connectionBanner.text = "Connected to Spare Phone ($foundIp)"
                    connectionBanner.setTextColor(Color.parseColor("#2E7D32"))
                } else {
                    connectionBanner.text = "Spare Phone not found yet - enter IP or tap Auto-Find"
                    connectionBanner.setTextColor(Color.parseColor("#E65100"))
                }
            }
        }
    }

    private fun startPrintJob() {
        val settings = readSettings()
        val targetIp = ipInput.text.toString().trim()

        if (!settings.directUsb && targetIp.isEmpty()) {
            statusBarText.text = "Enter Spare Phone IP or tap Auto-Find first."
            return
        }
        if (!settings.directUsb) {
            getSharedPreferences("g2012_prefs", Context.MODE_PRIVATE)
                .edit().putString("last_ip", targetIp).apply()
        }

        val runJob: () -> Unit = {
            printBtn.isEnabled = false
            progressBar.visibility = View.VISIBLE
            progressBar.progress = 5
            thread {
                try {
                    updateStatus("Initializing Canon G2012 driver...", 10)
                    val initRes = GutenprintDriver.ensureInitialized(this)
                    if (initRes != "OK") throw IllegalStateException(initRes)

                    val sheetCount = PageComposer.getTotalSheetCount(this, currentSource, settings)
                    if (sheetCount <= 0) throw IllegalStateException("No pages to print.")

                    val totalWork = sheetCount * settings.copies
                    var done = 0
                    val outFile = File(cacheDir, "g2012_job.prn")

                    for (copy in 1..settings.copies) {
                        for (sheetIdx in 0 until sheetCount) {
                            val pct = 15 + ((done * 80) / totalWork)
                            updateStatus("Rendering sheet ${sheetIdx + 1}/$sheetCount (Copy $copy/${settings.copies})...", pct)
                            val bmp = PageComposer.composeSheet(
                                this, currentSource, settings, sheetIdx,
                                dpi = settings.quality.dpi,
                                applyBwPreviewFilter = false
                            )
                            val res = GutenprintDriver.renderBitmapToCanonFile(
                                bmp, settings.isColor,
                                settings.paperSize.gutenprintName,
                                settings.mediaType,
                                outFile
                            )
                            bmp.recycle()
                            if (!res.startsWith("OK:")) throw IllegalStateException(res)

                            val kb = outFile.length() / 1024
                            if (settings.directUsb) {
                                updateStatus("Sending sheet ${sheetIdx + 1} ($kb KB) via USB...", pct + 5)
                                sendFileDirectUsb(outFile)
                            } else {
                                updateStatus("Sending sheet ${sheetIdx + 1} ($kb KB) to $targetIp...", pct + 5)
                                sendFileOverWifi(targetIp, outFile)
                            }
                            done++
                        }
                    }
                    outFile.delete()
                    updateStatus("DONE! Printed $done sheet(s) on Canon G2012.", 100)
                } catch (e: Exception) {
                    updateStatus("Print failed: ${e.message}", 0)
                } finally {
                    runOnUiThread { printBtn.isEnabled = true }
                }
            }
        }

        if (settings.directUsb) requestUsbPermissionAndRun(runJob) else runJob()
    }

    private fun sendFileOverWifi(ip: String, file: File) {
        NetworkUtils.connectToServer(this, ip, PrintBridgeService.PRINT_PORT).use { socket ->
            val out = socket.getOutputStream()
            FileInputStream(file).use { fis -> fis.copyTo(out, 16384) }
            out.flush()
        }
    }

    private fun sendFileDirectUsb(file: File) {
        val device = findUsbPrinter() ?: throw IllegalStateException("No USB printer found.")
        var usbInterface: UsbInterface? = null
        var outEndpoint: UsbEndpoint? = null
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_PRINTER) {
                for (j in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(j)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_OUT) {
                        usbInterface = intf
                        outEndpoint = ep
                        break
                    }
                }
            }
            if (outEndpoint != null) break
        }
        if (usbInterface == null || outEndpoint == null) throw IllegalStateException("USB printer endpoint not found.")
        val conn = usbManager.openDevice(device) ?: throw IllegalStateException("Cannot open USB printer.")
        try {
            if (!conn.claimInterface(usbInterface, true)) throw IllegalStateException("Cannot claim USB interface.")
            val buf = ByteArray(16384)
            FileInputStream(file).use { fis ->
                var n: Int
                while (fis.read(buf).also { n = it } != -1) {
                    val chunk = if (n == buf.size) buf else buf.copyOf(n)
                    val sent = conn.bulkTransfer(outEndpoint, chunk, chunk.size, 15000)
                    if (sent < 0) throw IllegalStateException("USB transfer error.")
                }
            }
        } finally {
            try { conn.releaseInterface(usbInterface) } catch (_: Exception) {}
            try { conn.close() } catch (_: Exception) {}
        }
    }

    private fun requestUsbPermissionAndRun(action: () -> Unit) {
        val printer = findUsbPrinter()
        if (printer == null) {
            serverStatusText.text = "Canon G2012 not found! Turn printer ON and connect via OTG."
            statusBarText.text = "Canon G2012 not found via OTG."
            return
        }
        if (usbManager.hasPermission(printer)) {
            action()
        } else {
            pendingUsbAction = action
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), flags)
            usbManager.requestPermission(printer, pi)
        }
    }

    private fun startBridgeService() {
        val intent = Intent(this, PrintBridgeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        serverStatusText.text = "Starting server..."
        updateServerUi()
    }

    private fun findUsbPrinter(): UsbDevice? {
        return usbManager.deviceList.values.firstOrNull { device ->
            device.vendorId == 0x04A9 || (0 until device.interfaceCount).any { idx ->
                device.getInterface(idx).interfaceClass == UsbConstants.USB_CLASS_PRINTER
            }
        }
    }

    private fun updateServerUi() {
        val ip = NetworkUtils.getBestLocalIp()
        serverIpText.text = "IP: $ip   |   Port: ${PrintBridgeService.PRINT_PORT}"
        if (PrintBridgeService.isRunning) {
            serverToggleBtn.text = "Stop Printer Server"
            (serverToggleBtn.background as? GradientDrawable)?.setColor(Color.parseColor("#C62828"))
            serverStatusText.text = PrintBridgeService.lastStatus
        } else {
            serverToggleBtn.text = "Start Printer Server"
            (serverToggleBtn.background as? GradientDrawable)?.setColor(Color.parseColor("#2E7D32"))
        }
    }

    private fun updateStatus(msg: String, progress: Int) {
        runOnUiThread {
            statusBarText.text = msg
            progressBar.progress = progress
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
    }
}
