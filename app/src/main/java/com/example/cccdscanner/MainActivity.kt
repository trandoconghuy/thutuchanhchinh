package com.example.cccdscanner

import android.Manifest
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.min

class MainActivity : AppCompatActivity() {
    private val blue = Color.rgb(36, 87, 214)
    private val deepBlue = Color.rgb(17, 50, 124)
    private val navy = Color.rgb(25, 42, 73)
    private val paleBlue = Color.rgb(237, 244, 255)
    private val border = Color.rgb(207, 218, 236)
    private val green = Color.rgb(19, 112, 61)
    private val red = Color.rgb(184, 35, 48)
    private val fields = linkedMapOf<String, EditText>()
    private val contract = ContractData()
    private lateinit var contentHost: FrameLayout
    private lateinit var tenantStore: TenantStore
    private lateinit var cameraExecutor: ExecutorService
    private var tenantSignature: Bitmap? = null
    private var landlordSignature: Bitmap? = null
    private var scannerDialog: Dialog? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var pendingGalleryScan = false

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showCameraScanner() else toast("Cần quyền camera để quét mã QR trên CCCD")
    }

    private val pickQrImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        pendingGalleryScan = true
        val image = runCatching { InputImage.fromFilePath(this, uri) }.getOrElse {
            pendingGalleryScan = false
            toast("Không thể đọc ảnh đã chọn")
            return@registerForActivityResult
        }
        BarcodeScanning.getClient().process(image)
            .addOnSuccessListener { codes ->
                val raw = codes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }?.rawValue
                if (raw.isNullOrBlank()) toast("Ảnh không có mã QR CCCD hợp lệ") else acceptQr(raw)
            }
            .addOnFailureListener { toast("Không thể phân tích mã QR trong ảnh") }
            .addOnCompleteListener { pendingGalleryScan = false }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tenantStore = TenantStore(this)
        cameraExecutor = Executors.newSingleThreadExecutor()
        buildShell()
        showForm()
    }

    private fun buildShell() {
        val root = findViewById<FrameLayout>(R.id.appRoot)
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(contentWidth(), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL)
        }
        root.addView(shell)

        val topBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(9), dp(10), dp(9))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(deepBlue, blue)).apply {
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, dp(18).toFloat(), dp(18).toFloat(), dp(18).toFloat(), dp(18).toFloat())
            }
        }
        val topTitle = TextView(this).apply {
            text = "▣  Thông tin hợp đồng"
            setTextColor(Color.WHITE)
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
        }
        topBar.addView(topTitle, LinearLayout.LayoutParams(0, dp(48), 1f))
        topBar.addView(iconButton("↻", "Làm mới hợp đồng") { confirmReset() })
        topBar.addView(space(dp(8), 1))
        topBar.addView(iconButton("×", "Đóng ứng dụng", red) { finish() })
        shell.addView(topBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(66)))

        contentHost = FrameLayout(this).apply { clipToPadding = false }
        shell.addView(contentHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        shell.addView(buildBottomNavigation())
    }

    private fun buildBottomNavigation(): View {
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dp(7), dp(6), dp(7), dp(8))
            background = rounded(Color.WHITE, 17f, border)
            elevation = dp(10).toFloat()
        }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER }
        val actions = listOf(
            Triple("Nhập", Color.rgb(223, 234, 255)) { showForm() },
            Triple("Xem", Color.rgb(239, 243, 249)) { showPreview() },
            Triple("Ký tên", Color.rgb(223, 234, 255)) { showSignatures() },
            Triple("PDF", Color.rgb(39, 100, 225)) { openPdf() },
            Triple("Lưu hình", Color.rgb(14, 105, 143)) { saveImage() },
            Triple("Chia sẻ", deepBlue) { sharePdf() }
        )
        actions.forEachIndexed { index, item ->
            val button = MaterialButton(this).apply {
                text = item.first
                textSize = 13f
                isAllCaps = false
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (index < 3) navy else Color.WHITE)
                backgroundTintList = ColorStateList.valueOf(item.second)
                cornerRadius = dp(11)
                insetTop = 0; insetBottom = 0
                minWidth = dp(if (item.first == "Lưu hình" || item.first == "Chia sẻ") 76 else 64)
                setOnClickListener { press(this); item.third.invoke() }
            }
            row.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply { marginEnd = dp(5) })
        }
        scroll.addView(row)
        return scroll.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)) }
    }

    private fun showForm() {
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(18), dp(14), dp(100))
        }
        scroll.addView(body)
        sectionTitle(body, "Thông tin chung", "Thời gian và địa điểm ký kết")
        field(body, "time", "Giờ / Phút", contract.time)
        field(body, "date", "Ngày lập HĐ", contract.date)
        field(body, "place", "Địa điểm lập", contract.place, true)

        sectionTitle(body, "Bên cho thuê (Bên A)", "Thông tin chủ sở hữu nhà")
        personFields(body, "landlord", contract.landlord, false)

        sectionTitle(body, "Bên thuê (Bên B)", "Quét CCCD hoặc chọn hồ sơ đã lưu")
        body.addView(buildScanPanel())
        body.addView(buildLibraryPanel())
        personFields(body, "tenant", contract.tenant, true)
        val saveTenant = actionButton("Lưu người thuê vào thư viện", green) {
            collectForm()
            if (tenantStore.save(contract.tenant)) toast("Đã lưu hồ sơ người thuê")
            else toast("Vui lòng nhập họ tên và số CCCD")
        }
        body.addView(saveTenant, margins(dp(44), top = 12, bottom = 18))

        sectionTitle(body, "Nội dung thỏa thuận", "Thông tin được đưa trực tiếp vào bản hợp đồng")
        field(body, "area", "Diện tích (m²)", contract.area)
        field(body, "duration", "Thời hạn", contract.duration)
        field(body, "monthlyRent", "Giá thuê/tháng", contract.monthlyRent)
        swapContent(scroll)
    }

    private fun buildScanPanel(): View {
        val card = card(paleBlue)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(13), dp(12), dp(13), dp(13)) }
        box.addView(label("Quét QR CCCD", 14f, deepBlue, true))
        box.addView(label("Dữ liệu được xử lý trực tiếp trên thiết bị, không tải lên máy chủ.", 12f, Color.rgb(75, 91, 119), false), margins(-2, top = 4, bottom = 8))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(actionButton("Quét bằng camera", blue) { startCameraScan() }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(5) })
        row.addView(actionButton("Chọn từ thư viện", Color.WHITE, blue) { pickQrImage.launch("image/*") }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(5) })
        box.addView(row)
        card.addView(box)
        return card.apply { layoutParams = margins(ViewGroup.LayoutParams.WRAP_CONTENT, top = 6, bottom = 12) }
    }

    private fun buildLibraryPanel(): View {
        val card = card(Color.rgb(245, 249, 255))
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(13), dp(11), dp(13), dp(11)) }
        box.addView(label("THƯ VIỆN NGƯỜI THUÊ", 12f, Color.rgb(67, 84, 112), true))
        val choose = actionButton("Chọn khách thuê từ danh sách  ▾", Color.WHITE, blue) { showTenantLibrary() }
        choose.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        box.addView(choose, margins(dp(46), top = 5, bottom = 4))
        box.addView(label("Chạm để chọn hồ sơ; nhấn giữ tên trong hộp thoại để xóa.", 11f, Color.rgb(102, 116, 139), false))
        card.addView(box)
        return card.apply { layoutParams = margins(ViewGroup.LayoutParams.WRAP_CONTENT, bottom = 10) }
    }

    private fun personFields(parent: LinearLayout, prefix: String, person: PersonData, includeCurrent: Boolean) {
        field(parent, "$prefix.name", "Họ và tên", person.name)
        field(parent, "$prefix.birthDate", "Sinh năm", person.birthDate)
        field(parent, "$prefix.citizenId", "Số CCCD", person.citizenId)
        field(parent, "$prefix.issueDate", "Ngày cấp", person.issueDate)
        field(parent, "$prefix.issuePlace", "Nơi cấp", person.issuePlace)
        field(parent, "$prefix.permanentAddress", "Hộ khẩu thường trú", person.permanentAddress, true)
        if (includeCurrent) field(parent, "$prefix.currentAddress", "Nơi ở hiện tại", person.currentAddress, true)
    }

    private fun field(parent: LinearLayout, key: String, caption: String, value: String, multiLine: Boolean = false) {
        parent.addView(label(caption, 12f, navy, true), margins(ViewGroup.LayoutParams.WRAP_CONTENT, top = 8, bottom = 4))
        val input = EditText(this).apply {
            setText(value)
            textSize = 16f
            setTextColor(Color.rgb(21, 28, 41))
            setHintTextColor(Color.rgb(140, 150, 168))
            setPadding(dp(13), dp(8), dp(13), dp(8))
            background = rounded(Color.WHITE, 7f, border)
            minHeight = dp(if (multiLine) 52 else 46)
            maxLines = if (multiLine) 3 else 1
            isSingleLine = !multiLine
        }
        fields[key] = input
        parent.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun sectionTitle(parent: LinearLayout, title: String, subtitle: String) {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, dp(6)) }
        wrap.addView(label(title, 17f, navy, true))
        wrap.addView(label(subtitle, 11f, Color.rgb(103, 116, 139), false), margins(ViewGroup.LayoutParams.WRAP_CONTENT, top = 2))
        val line = View(this).apply { setBackgroundColor(Color.rgb(218, 225, 236)) }
        wrap.addView(line, margins(1, top = 7))
        parent.addView(wrap)
    }

    private fun collectForm() {
        fun value(key: String, fallback: String) = fields[key]?.text?.toString()?.trim()?.ifBlank { fallback } ?: fallback
        contract.time = value("time", contract.time)
        contract.date = value("date", contract.date)
        contract.place = value("place", contract.place)
        contract.area = value("area", contract.area)
        contract.duration = value("duration", contract.duration)
        contract.monthlyRent = value("monthlyRent", contract.monthlyRent)
        collectPerson("landlord", contract.landlord)
        collectPerson("tenant", contract.tenant)
    }

    private fun collectPerson(prefix: String, person: PersonData) {
        fun v(name: String, old: String) = fields["$prefix.$name"]?.text?.toString()?.trim()?.ifBlank { old } ?: old
        person.name = v("name", person.name)
        person.birthDate = v("birthDate", person.birthDate)
        person.citizenId = v("citizenId", person.citizenId)
        person.issueDate = v("issueDate", person.issueDate)
        person.issuePlace = v("issuePlace", person.issuePlace)
        person.permanentAddress = v("permanentAddress", person.permanentAddress)
        person.currentAddress = v("currentAddress", person.currentAddress)
    }

    private fun showPreview() {
        collectForm()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(9), dp(10), dp(9), dp(90))
        }
        val hint = label("Chạm trực tiếp vào nội dung hợp đồng để quay lại chỉnh sửa.", 13f, blue, true).apply {
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(224, 237, 255), 12f)
            setOnClickListener { showForm() }
        }
        container.addView(hint, margins(dp(44), bottom = 10))
        val image = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(ContractRenderer.renderBitmap(contract, tenantSignature, landlordSignature))
            background = rounded(Color.WHITE, 2f, Color.rgb(218, 224, 234))
            elevation = dp(6).toFloat()
            contentDescription = "Bản xem trước hợp đồng thuê nhà"
            setOnClickListener { showForm() }
        }
        container.addView(image, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val scroll = ScrollView(this).apply { addView(container) }
        swapContent(scroll)
    }

    private fun showSignatures() {
        collectForm()
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(13), dp(18), dp(13), dp(100)) }
        body.addView(label("Ký tên trên điện thoại", 21f, Color.rgb(11, 26, 50), true))
        body.addView(label("Ký trong hai khung lớn. Chữ ký sẽ được căn giữa và đưa đúng vị trí trên hợp đồng.", 13f, Color.rgb(92, 106, 130), false), margins(ViewGroup.LayoutParams.WRAP_CONTENT, top = 4, bottom = 12))
        val tenantPad = signatureCard("Bên thuê nhà", contract.tenant.name, tenantSignature) { tenantSignature = null }
        val landlordPad = signatureCard("Bên cho thuê nhà", contract.landlord.name, landlordSignature) { landlordSignature = null }
        body.addView(tenantPad.first, margins(ViewGroup.LayoutParams.WRAP_CONTENT, bottom = 13))
        body.addView(landlordPad.first, margins(ViewGroup.LayoutParams.WRAP_CONTENT, bottom = 13))
        val apply = actionButton("Áp dụng chữ ký vào hợp đồng", blue) {
            tenantSignature = tenantPad.second.asBitmap()
            landlordSignature = landlordPad.second.asBitmap()
            toast("Đã cập nhật chữ ký")
            showPreview()
        }
        body.addView(apply, margins(dp(48), top = 2, bottom = 12))
        swapContent(ScrollView(this).apply { addView(body) })
    }

    private fun signatureCard(title: String, name: String, existing: Bitmap?, onClear: () -> Unit): Pair<View, SignatureView> {
        val card = card(Color.WHITE).apply { cardElevation = dp(3).toFloat() }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(11), dp(12), dp(12)) }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(label(title, 14f, Color.rgb(14, 34, 67), true), LinearLayout.LayoutParams(0, dp(34), 1f))
        head.addView(label(name, 12f, Color.rgb(91, 106, 131), false))
        body.addView(head)
        val pad = SignatureView(this).apply {
            background = rounded(Color.rgb(250, 252, 255), 10f, Color.rgb(149, 179, 225))
            setSignature(existing)
        }
        body.addView(pad, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(215)))
        val clear = actionButton("Xóa chữ ký", Color.rgb(255, 232, 234), red) { pad.clear(); onClear() }
        body.addView(clear, margins(dp(42), top = 8))
        card.addView(body)
        return card to pad
    }

    private fun openPdf() {
        collectForm()
        runCatching {
            val file = ContractRenderer.createPdf(this, contract, tenantSignature, landlordSignature)
            val uri = fileUri(file)
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
            toast("Đã tạo ${file.name}")
        }.onFailure {
            if (it is ActivityNotFoundException) toast("Đã tạo PDF nhưng thiết bị chưa có ứng dụng đọc PDF")
            else toast("Không thể tạo PDF: ${it.message ?: "lỗi không xác định"}")
        }
    }

    private fun sharePdf() {
        collectForm()
        runCatching {
            val file = ContractRenderer.createPdf(this, contract, tenantSignature, landlordSignature)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_SUBJECT, "Hợp đồng thuê nhà - ${contract.tenant.name}")
                putExtra(Intent.EXTRA_STREAM, fileUri(file))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Chia sẻ hợp đồng PDF"))
        }.onFailure { toast("Không thể chia sẻ PDF: ${it.message ?: "lỗi không xác định"}") }
    }

    private fun saveImage() {
        collectForm()
        runCatching {
            val directory = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir, "HopDong")
            check(directory.exists() || directory.mkdirs()) { "Không thể tạo thư mục ảnh" }
            val file = File(directory, "Hop_Dong_${safeFileStamp()}.png")
            FileOutputStream(file).use { out ->
                ContractRenderer.renderBitmap(contract, tenantSignature, landlordSignature, 3).compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf("image/png"), null)
            toast("Đã lưu ảnh hợp đồng: ${file.name}")
        }.onFailure { toast("Không thể lưu ảnh: ${it.message ?: "lỗi không xác định"}") }
    }

    private fun showTenantLibrary() {
        val tenants = tenantStore.load()
        if (tenants.isEmpty()) {
            toast("Thư viện chưa có hồ sơ người thuê")
            return
        }
        val labels = tenants.mapIndexed { index, p -> "${index + 1}. ${p.name} · ${p.citizenId.takeLast(4)}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Thư viện người thuê")
            .setItems(labels) { _, which ->
                contract.tenant = tenants[which].copy()
                fillTenantFields(contract.tenant)
                toast("Đã chọn ${contract.tenant.name}")
            }
            .setNeutralButton("Xóa hồ sơ") { _, _ -> showDeleteTenant(tenants) }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun showDeleteTenant(tenants: List<PersonData>) {
        val labels = tenants.map { "${it.name} · ${it.citizenId.takeLast(4)}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Chọn hồ sơ cần xóa")
            .setItems(labels) { _, which ->
                val target = tenants[which]
                AlertDialog.Builder(this).setMessage("Xóa ${target.name} khỏi thư viện?")
                    .setPositiveButton("Xóa") { _, _ -> tenantStore.delete(target.citizenId); toast("Đã xóa hồ sơ") }
                    .setNegativeButton("Hủy", null).show()
            }.setNegativeButton("Hủy", null).show()
    }

    private fun startCameraScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) showCameraScanner()
        else requestCamera.launch(Manifest.permission.CAMERA)
    }

    private fun showCameraScanner() {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = FrameLayout(this)
        val previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        root.addView(previewView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val overlay = TextView(this).apply {
            text = "Đưa mã QR trên CCCD vào giữa khung\nDữ liệu chỉ được xử lý trên thiết bị"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = GradientDrawable().apply { setColor(Color.argb(185, 9, 22, 45)); cornerRadius = dp(15).toFloat() }
        }
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply { setMargins(dp(18), dp(42), dp(18), 0) })
        val close = iconButton("×", "Đóng camera", red) { dialog.dismiss() }
        root.addView(close, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(36) })
        dialog.setContentView(root)
        dialog.setOnDismissListener { cameraProvider?.unbindAll(); scannerDialog = null }
        dialog.show()
        scannerDialog = dialog
        bindCamera(previewView)
    }

    private fun bindCamera(previewView: PreviewView) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analyzer = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            var accepted = false
            analyzer.setAnalyzer(cameraExecutor) { proxy ->
                if (accepted) { proxy.close(); return@setAnalyzer }
                scanProxy(proxy) { raw ->
                    if (!accepted) {
                        accepted = true
                        runOnUiThread { scannerDialog?.dismiss(); acceptQr(raw) }
                    }
                }
            }
            runCatching {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analyzer)
            }.onFailure { toast("Không thể khởi động camera") }
        }, ContextCompat.getMainExecutor(this))
    }

    @ExperimentalGetImage
    private fun scanProxy(proxy: ImageProxy, onFound: (String) -> Unit) {
        val media = proxy.image
        if (media == null) { proxy.close(); return }
        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        BarcodeScanning.getClient().process(image)
            .addOnSuccessListener { codes ->
                codes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE && !it.rawValue.isNullOrBlank() }?.rawValue?.let(onFound)
            }.addOnCompleteListener { proxy.close() }
    }

    private fun acceptQr(raw: String) {
        val parts = raw.split('|')
        if (parts.size < 6) {
            toast("Mã QR không đúng định dạng CCCD")
            return
        }
        contract.tenant.citizenId = parts[0].trim()
        contract.tenant.name = parts[2].trim().uppercase()
        contract.tenant.birthDate = formatQrDate(parts[3].trim())
        contract.tenant.permanentAddress = parts[5].trim()
        contract.tenant.issueDate = if (parts.size > 6) formatQrDate(parts[6].trim()) else contract.tenant.issueDate
        if (contract.tenant.currentAddress.isBlank()) contract.tenant.currentAddress = contract.place
        fillTenantFields(contract.tenant)
        toast("Đã nhập thông tin từ CCCD")
    }

    private fun fillTenantFields(person: PersonData) {
        fields["tenant.name"]?.setText(person.name)
        fields["tenant.birthDate"]?.setText(person.birthDate)
        fields["tenant.citizenId"]?.setText(person.citizenId)
        fields["tenant.issueDate"]?.setText(person.issueDate)
        fields["tenant.issuePlace"]?.setText(person.issuePlace)
        fields["tenant.permanentAddress"]?.setText(person.permanentAddress)
        fields["tenant.currentAddress"]?.setText(person.currentAddress)
    }

    private fun formatQrDate(value: String): String = if (value.length == 8 && value.all(Char::isDigit))
        "${value.substring(0, 2)}/${value.substring(2, 4)}/${value.substring(4)}" else value

    private fun confirmReset() {
        AlertDialog.Builder(this).setTitle("Làm mới hợp đồng")
            .setMessage("Khôi phục dữ liệu mẫu và xóa chữ ký hiện tại?")
            .setPositiveButton("Làm mới") { _, _ ->
                val fresh = ContractData()
                contract.time = fresh.time; contract.date = fresh.date; contract.place = fresh.place
                contract.landlord = fresh.landlord; contract.tenant = fresh.tenant
                contract.area = fresh.area; contract.duration = fresh.duration; contract.monthlyRent = fresh.monthlyRent
                tenantSignature = null; landlordSignature = null; fields.clear(); showForm()
            }.setNegativeButton("Hủy", null).show()
    }

    private fun swapContent(view: View) {
        contentHost.removeAllViews()
        contentHost.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        view.alpha = 0f; view.translationY = dp(10).toFloat()
        view.animate().alpha(1f).translationY(0f).setDuration(220).start()
    }

    private fun card(color: Int) = MaterialCardView(this).apply {
        radius = dp(13).toFloat(); strokeColor = Color.rgb(185, 209, 246); strokeWidth = dp(1)
        cardElevation = 0f; setCardBackgroundColor(color)
    }

    private fun actionButton(textValue: String, backgroundColor: Int, textColor: Int = Color.WHITE, action: () -> Unit): MaterialButton =
        MaterialButton(this).apply {
            text = textValue; textSize = 13f; isAllCaps = false; typeface = Typeface.DEFAULT_BOLD
            setTextColor(textColor); backgroundTintList = ColorStateList.valueOf(backgroundColor)
            cornerRadius = dp(9); insetTop = 0; insetBottom = 0
            strokeWidth = if (backgroundColor == Color.WHITE) dp(1) else 0
            strokeColor = ColorStateList.valueOf(if (backgroundColor == Color.WHITE) blue else backgroundColor)
            setOnClickListener { press(this); action() }
        }

    private fun iconButton(textValue: String, description: String, color: Int = Color.rgb(245, 249, 255), action: () -> Unit): MaterialButton =
        MaterialButton(this).apply {
            text = textValue; contentDescription = description; textSize = 24f; isAllCaps = false
            setTextColor(if (color == red) Color.WHITE else Color.rgb(15, 91, 150))
            backgroundTintList = ColorStateList.valueOf(color); cornerRadius = dp(12)
            insetTop = 0; insetBottom = 0; minWidth = 0
            setPadding(0, 0, 0, dp(2))
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            setOnClickListener { press(this); action() }
        }

    private fun label(value: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun rounded(fill: Int, radius: Float, stroke: Int? = null) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; setColor(fill); cornerRadius = dp(radius.toInt()).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun margins(height: Int, top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply {
        topMargin = dp(top); bottomMargin = dp(bottom)
    }

    private fun space(width: Int, height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(width, height) }
    private fun press(view: View) { view.animate().scaleX(.97f).scaleY(.97f).setDuration(70).withEndAction { view.animate().scaleX(1f).scaleY(1f).setDuration(120).start() }.start() }
    private fun fileUri(file: File): Uri = FileProvider.getUriForFile(this, "$packageName.files", file)
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + .5f).toInt()
    private fun contentWidth(): Int = min(resources.displayMetrics.widthPixels, dp(860))

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
        tenantSignature?.recycle()
        landlordSignature?.recycle()
        super.onDestroy()
    }
}
