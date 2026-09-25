package com.example.cccdscanner

import android.Manifest
import android.app.DatePickerDialog
import android.app.Dialog
import android.app.TimePickerDialog
import android.content.ContentValues
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaScannerConnection
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.provider.MediaStore
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Camera
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
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
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.Locale
import java.util.Calendar
import java.util.Date
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class MainActivity : AppCompatActivity() {
    private data class ScannedCitizen(
        val citizenId: String,
        val oldId: String,
        val name: String,
        val birthDate: String,
        val gender: String,
        val permanentAddress: String,
        val issueDate: String
    )

    private data class GalleryScanState(
        val original: Bitmap,
        var potentialArea: Rect? = null
    )

    private data class GalleryCandidate(
        val bitmap: Bitmap,
        val rotation: Int,
        val recycleAfterUse: Boolean
    )

    private val blue = Color.rgb(36, 87, 214)
    private val deepBlue = Color.rgb(17, 50, 124)
    private val navy = Color.rgb(25, 42, 73)
    private val paleBlue = Color.rgb(237, 244, 255)
    private val border = Color.rgb(207, 218, 236)
    private val green = Color.rgb(19, 112, 61)
    private val red = Color.rgb(184, 35, 48)
    private val fields = linkedMapOf<String, EditText>()
    private val contract = ContractData()
    private val ct01Fields = linkedMapOf<String, EditText>()
    private var ct01Data = Ct01Data()
    private lateinit var contentHost: FrameLayout
    private lateinit var topBar: LinearLayout
    private lateinit var bottomNavigation: View
    private lateinit var tenantStore: TenantStore
    private lateinit var cameraExecutor: ExecutorService
    private var tenantSignature: Bitmap? = null
    private var landlordSignature: Bitmap? = null
    private var ct01DeclarantSignature: Bitmap? = null
    private var ct01OwnerSignature: Bitmap? = null
    private var ct01HeadSignature: Bitmap? = null
    private var ct01GuardianSignature: Bitmap? = null
    private var ct01Step = 0
    private var ct01ActiveSignatureView: SignatureView? = null
    private var ct01ActiveSignatureTarget: String? = null
    private var contractStep = 0
    private var contractWizardVisible = false
    private var contractTenantPad: SignatureView? = null
    private var contractLandlordPad: SignatureView? = null
    private var scannerDialog: Dialog? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var boundCamera: Camera? = null
    private var scannerOverlay: QrScannerOverlayView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val frameInFlight = AtomicBoolean(false)
    private val barcodeScanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        )
    }
    private val galleryBarcodeScanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .enableAllPotentialBarcodes()
                .build()
        )
    }
    private var pendingGalleryScan = false
    private var pendingCt01ScanTarget: String? = null
    private var identityResultVisible = false
    private var templateSelectionVisible = false
    private var ct01Visible = false
    private var lastScannedCitizen: ScannedCitizen? = null
    private var pendingLocationTarget: String? = null
    private var contractLocationRequested = false
    private var ct01LocationRequested = false

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showCameraScanner() else toast("Cần quyền camera để quét mã QR trên CCCD")
    }

    private val requestLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) resolveCurrentLocation()
        else toast("Không có quyền vị trí. Bạn vẫn có thể chọn tỉnh/thành và phường/xã thủ công.")
    }

    private val pickQrImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) {
            pendingCt01ScanTarget = null
            return@registerForActivityResult
        }
        analyzeGalleryQr(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tenantStore = TenantStore(this)
        cameraExecutor = Executors.newSingleThreadExecutor()
        buildShell()
        showTemplateSelection(emptyCitizen())
    }

    private fun buildShell() {
        val root = findViewById<FrameLayout>(R.id.appRoot)
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(contentWidth(), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL)
        }
        root.addView(shell)

        topBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(9), dp(10), dp(9))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(deepBlue, blue)).apply {
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, dp(18).toFloat(), dp(18).toFloat(), dp(18).toFloat(), dp(18).toFloat())
            }
        }
        val topLogo = ImageView(this).apply {
            setImageResource(R.drawable.app_icon)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = rounded(Color.WHITE, 10f)
            contentDescription = "Biểu trưng chuyển đổi văn bản số"
        }
        topBar.addView(topLogo, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(9) })
        val titleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label("CHUYỂN ĐỔI VĂN BẢN SỐ", 9f, Color.rgb(190, 214, 255), true).apply { letterSpacing = .1f })
            addView(label("Thông tin hợp đồng", 18f, Color.WHITE, true))
        }
        topBar.addView(titleBlock, LinearLayout.LayoutParams(0, dp(48), 1f))
        topBar.addView(iconButton("↻", "Làm mới hợp đồng") { confirmReset() })
        topBar.addView(space(dp(7), 1))
        topBar.addView(iconButton("×", "Đóng ứng dụng", red) { finish() })
        shell.addView(topBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(70)))

        contentHost = FrameLayout(this).apply { clipToPadding = false }
        shell.addView(contentHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        shell.addView(buildCopyrightBar(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)))
        bottomNavigation = buildBottomNavigation()
        shell.addView(bottomNavigation)
    }

    private fun buildCopyrightBar(): View = TextView(this).apply {
        text = "🛡  Bản quyền: trandoconghuy@gmail.com"
        textSize = 10f
        setTextColor(Color.rgb(111, 121, 140))
        gravity = Gravity.CENTER
        setPadding(dp(8), 0, dp(8), 0)
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.rgb(244, 247, 252), Color.rgb(235, 241, 251), Color.rgb(244, 247, 252)))
        contentDescription = "Bản quyền trandoconghuy@gmail.com"
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
        showContractWizard()
    }

    private fun showContractWizard() {
        identityResultVisible = false; templateSelectionVisible = false; ct01Visible = false
        contractWizardVisible = true
        topBar.visibility = View.GONE; bottomNavigation.visibility = View.GONE
        fields.clear(); contractTenantPad = null; contractLandlordPad = null
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(246, 245, 241)) }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(11), dp(14), dp(11)); setBackgroundColor(Color.rgb(22, 35, 63)) }
        header.addView(label("HĐ", 11f, Color.rgb(22, 35, 63), true).apply { gravity = Gravity.CENTER; background = rounded(Color.rgb(184, 134, 46), 18f) }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(11) })
        val title = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(label("Hợp đồng thuê nhà", 15f, Color.WHITE, true)); addView(label("Bước ${contractStep + 1} / 7", 11f, Color.rgb(190, 199, 218), false), margins(ViewGroup.LayoutParams.WRAP_CONTENT, top = 2)) }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(iconButton("×", "Đóng hợp đồng") { showTemplateSelection(lastScannedCitizen ?: emptyCitizen()) }, LinearLayout.LayoutParams(dp(40), dp(40)))
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))
        val progress = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), dp(10), dp(14), dp(7)) }
        repeat(7) { index -> progress.addView(View(this).apply { background = rounded(if (index <= contractStep) Color.rgb(184, 134, 46) else Color.rgb(227, 225, 218), 3f) }, LinearLayout.LayoutParams(0, dp(4), 1f).apply { if (index < 6) marginEnd = dp(4) }) }
        root.addView(progress)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(24)) }
        buildContractWizardStep(body)
        if (contractStep == 5) root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        else root.addView(ScrollView(this).apply { isFillViewport = true; isSmoothScrollingEnabled = true; addView(body) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val navigation = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(dp(16), dp(10), dp(16), dp(14)); setBackgroundColor(Color.rgb(246, 245, 241)) }
        if (contractStep > 0) navigation.addView(actionButton("Quay lại", Color.TRANSPARENT, navy) { persistContractWizardStep(); contractStep--; showContractWizard() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
        val nextLabel = when (contractStep) { 5 -> "Kiểm tra xong"; 6 -> "Về biểu mẫu"; else -> "Tiếp tục" }
        navigation.addView(actionButton(nextLabel, Color.rgb(22, 35, 63)) {
            persistContractWizardStep()
            if (contractStep == 6) showTemplateSelection(lastScannedCitizen ?: emptyCitizen())
            else if (validateContractWizardStep()) { contractStep++; showContractWizard() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = if (contractStep > 0) dp(6) else 0 })
        root.addView(navigation); swapContent(root)
        if (contractStep == 0 && contract.province.isBlank() && !contractLocationRequested) {
            contractLocationRequested = true
            requestAutomaticLocation("contract")
        }
    }

    private fun buildContractWizardStep(body: LinearLayout) {
        when (contractStep) {
            0 -> {
                wizardHeading(body, "B1 · Thông tin hợp đồng", "Thời gian và địa điểm lập", "Giờ, ngày được lấy theo thiết bị. Địa điểm có thể tự nhận diện hoặc chọn thủ công.")
                smartField(body, "time", "Giờ / Phút", contract.time) { showTimePicker(fields.getValue("time")) }
                smartField(body, "date", "Ngày lập hợp đồng", contract.date) { showDatePicker(fields.getValue("date")) }
                field(body, "placeDetail", "1. Số nhà, tên đường, thôn/ấp/khu phố", contract.placeDetail, true)
                smartField(body, "contractWard", "2. Phường / Xã", contract.ward) {
                    if (contract.province.isBlank()) toast("Vui lòng chọn tỉnh / thành phố trước")
                    else showWardPicker(contract.province) { collectForm(); contract.ward = it; contract.place = composeAddress(contract.placeDetail, contract.ward, contract.province); showContractWizard() }
                }
                smartField(body, "contractProvince", "3. Tỉnh / Thành phố", contract.province) {
                    showCt01ListPicker("Chọn tỉnh / thành phố", provinceChoices()) { collectForm(); contract.province = it; contract.ward = ""; contract.place = composeAddress(contract.placeDetail, contract.ward, contract.province); showContractWizard() }
                }
                body.addView(actionButton("⌖  Tự động lấy vị trí hiện tại", Color.WHITE, deepBlue) { collectForm(); requestAutomaticLocation("contract") }, margins(dp(46), top = 8))
            }
            1 -> {
                wizardHeading(body, "B2 · Bên cho thuê", "Thông tin Bên A", "Quét QR CCCD để điền nhanh, sau đó kiểm tra các trường còn thiếu.")
                buildContractScanMethods(body, "contract_landlord")
                body.addView(wizardIdentityResult(contract.landlord.name, contract.landlord.citizenId), margins(ViewGroup.LayoutParams.WRAP_CONTENT, bottom = 10))
                personFields(body, "landlord", contract.landlord, false)
            }
            2 -> {
                wizardHeading(body, "B3 · Bên thuê", "Thông tin Bên B", "Quét QR CCCD hoặc chọn ảnh từ thư viện; dữ liệu chỉ xử lý trên thiết bị.")
                buildContractScanMethods(body, "contract_tenant")
                body.addView(wizardIdentityResult(contract.tenant.name, contract.tenant.citizenId), margins(ViewGroup.LayoutParams.WRAP_CONTENT, bottom = 10))
                personFields(body, "tenant", contract.tenant, true)
            }
            3 -> {
                wizardHeading(body, "B4 · Nội dung thỏa thuận", "Điều khoản thuê nhà", "Các giá trị được đưa trực tiếp vào mẫu hợp đồng PDF.")
                smartField(body, "area", "Diện tích thuê", contract.area) { showAreaPicker(fields.getValue("area")) }
                smartField(body, "duration", "Thời hạn thuê", contract.duration) { showDurationPicker(fields.getValue("duration")) }
                smartField(body, "monthlyRent", "Giá thuê mỗi tháng", contract.monthlyRent) { showRentPicker(fields.getValue("monthlyRent")) }
            }
            4 -> {
                wizardHeading(body, "B5 · Ký xác nhận", "Chữ ký hai bên", "Ký trong từng khung; chữ ký được căn đúng vị trí trên hợp đồng.")
                val landlord = signatureCard("Bên cho thuê (Bên A)", contract.landlord.name, landlordSignature) { landlordSignature = null }
                contractLandlordPad = landlord.second; body.addView(landlord.first, margins(ViewGroup.LayoutParams.WRAP_CONTENT, bottom = 12))
                val tenant = signatureCard("Bên thuê (Bên B)", contract.tenant.name, tenantSignature) { tenantSignature = null }
                contractTenantPad = tenant.second; body.addView(tenant.first)
            }
            else -> {
                if (contractStep == 5) {
                wizardHeading(body, "B6 · Xem trước", "Kiểm tra hợp đồng", "Vùng xem trước hoạt động độc lập: chụm để phóng to, kéo để di chuyển, chạm hai lần để đặt lại.")
                body.addView(ZoomableImageView(this).apply {
                    setImageBitmap(ContractRenderer.renderBitmap(this@MainActivity, contract, tenantSignature, landlordSignature, 2))
                    background = rounded(Color.rgb(232, 236, 243), 8f, Color.rgb(210, 214, 222)); contentDescription = "Bản xem trước hợp đồng. Có thể phóng to và di chuyển bằng cảm ứng."
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                } else {
                    completionHeading(body, "B7 · Hoàn tất", "Hợp đồng đã sẵn sàng", "Xuất file, lưu hình hoặc chia sẻ qua ứng dụng trên điện thoại.")
                    wizardExportRow(body, "PDF", "Xuất file PDF", "Hai trang A4 đúng mẫu hợp đồng") { openPdf() }
                    wizardExportRow(body, "PNG", "Lưu hình ảnh", "Ghép hai trang trong một ảnh") { saveImage() }
                    wizardExportRow(body, "↗", "Chia sẻ PDF", "Gửi qua Zalo, email, Drive hoặc ứng dụng khác") { sharePdf() }
                }
            }
        }
    }

    private fun buildContractScanMethods(parent: LinearLayout, target: String) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(actionButton("Quét QR căn cước", Color.rgb(22, 35, 63)) { collectForm(); pendingCt01ScanTarget = target; startCameraScan() }, LinearLayout.LayoutParams(0, dp(47), 1f).apply { marginEnd = dp(5) })
        row.addView(actionButton("Chọn ảnh thư viện", Color.WHITE, deepBlue) { collectForm(); pendingCt01ScanTarget = target; pickQrImage.launch("image/*") }, LinearLayout.LayoutParams(0, dp(47), 1f).apply { marginStart = dp(5) })
        parent.addView(row, margins(ViewGroup.LayoutParams.WRAP_CONTENT, bottom = 12))
    }

    private fun persistContractWizardStep() {
        collectForm()
        contractLandlordPad?.let { landlordSignature = it.asBitmap() }
        contractTenantPad?.let { tenantSignature = it.asBitmap() }
    }

    private fun validateContractWizardStep(): Boolean {
        fun reject(text: String): Boolean { toast(text); return false }
        return when (contractStep) {
            0 -> if (contract.time.isBlank() || contract.date.isBlank() || contract.place.isBlank()) reject("Vui lòng nhập đủ thời gian, ngày và địa điểm lập hợp đồng") else true
            1 -> if (contract.landlord.name.isBlank() || !contract.landlord.citizenId.matches(Regex("\\d{12}"))) reject("Vui lòng quét hoặc nhập đủ thông tin Bên A") else true
            2 -> if (contract.tenant.name.isBlank() || !contract.tenant.citizenId.matches(Regex("\\d{12}"))) reject("Vui lòng quét hoặc nhập đủ thông tin Bên B") else true
            3 -> if (contract.area.isBlank() || contract.duration.isBlank() || contract.monthlyRent.isBlank()) reject("Vui lòng chọn đủ diện tích, thời hạn và giá thuê") else true
            else -> true
        }
    }

    private fun showContractLegacyForm() {
        showAppChrome()
        fields.clear()
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(18), dp(14), dp(100))
        }
        scroll.addView(body)
        sectionTitle(body, "Thông tin chung", "Thời gian và địa điểm ký kết")
        smartField(body, "time", "Giờ / Phút", contract.time) { showTimePicker(fields.getValue("time")) }
        smartField(body, "date", "Ngày lập HĐ", contract.date) { showDatePicker(fields.getValue("date")) }
        field(body, "place", "Địa điểm lập", contract.place, true)

        sectionTitle(body, "Bên cho thuê (Bên A)", "Thông tin chủ sở hữu nhà")
        personFields(body, "landlord", contract.landlord, false)

        sectionTitle(body, "Bên thuê (Bên B)", "Thông tin người đại diện thuê nhà")
        personFields(body, "tenant", contract.tenant, true)

        sectionTitle(body, "Nội dung thỏa thuận", "Thông tin được đưa trực tiếp vào bản hợp đồng")
        smartField(body, "area", "Diện tích (m²)", contract.area) { showAreaPicker(fields.getValue("area")) }
        smartField(body, "duration", "Thời hạn", contract.duration) { showDurationPicker(fields.getValue("duration")) }
        smartField(body, "monthlyRent", "Giá thuê/tháng", contract.monthlyRent) { showRentPicker(fields.getValue("monthlyRent")) }
        swapContent(scroll)
    }

    private fun showScanHome() {
        contractWizardVisible = false
        identityResultVisible = false
        templateSelectionVisible = false
        ct01Visible = false
        topBar.visibility = View.GONE
        bottomNavigation.visibility = View.GONE
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(20), dp(12), dp(24))
        }
        page.addView(buildBrandHeader(), margins(ViewGroup.LayoutParams.WRAP_CONTENT, bottom = 18))
        page.addView(space(1, 0), LinearLayout.LayoutParams(1, 0, 1f))
        page.addView(buildScanPanel(), margins(ViewGroup.LayoutParams.WRAP_CONTENT, bottom = 14))
        page.addView(buildLibraryPanel(), margins(ViewGroup.LayoutParams.WRAP_CONTENT))
        swapContent(page)
    }

    private fun buildBrandHeader(): View {
        val card = MaterialCardView(this).apply {
            radius = dp(24).toFloat()
            cardElevation = dp(8).toFloat()
            setCardBackgroundColor(deepBlue)
            strokeWidth = 0
        }
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(12, 45, 111), Color.rgb(38, 101, 224))).apply {
                cornerRadius = dp(24).toFloat()
            }
        }
        val mark = FrameLayout(this).apply {
            background = rounded(Color.WHITE, 17f)
            elevation = dp(3).toFloat()
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.app_icon)
                contentDescription = "Biểu trưng phần mềm chuyển đổi văn bản số"
                setPadding(dp(7), dp(7), dp(7), dp(7))
            }, FrameLayout.LayoutParams(dp(58), dp(58), Gravity.CENTER))
        }
        row.addView(mark, LinearLayout.LayoutParams(dp(66), dp(66)).apply { marginEnd = dp(14) })
        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(label("PHẦN MỀM", 11f, Color.rgb(184, 210, 255), true).apply { letterSpacing = .16f })
        copy.addView(label("Chuyển đổi văn bản số", 20f, Color.WHITE, true), margins(ViewGroup.LayoutParams.WRAP_CONTENT, top = 2))
        copy.addView(label("Quét căn cước · Lập hợp đồng · Xuất A4", 11f, Color.rgb(221, 232, 255), false), margins(ViewGroup.LayoutParams.WRAP_CONTENT, top = 4))
        row.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(row)
        return card
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
            hint = exampleHint(key, caption)
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

    private fun smartField(parent: LinearLayout, key: String, caption: String, value: String, chooser: () -> Unit) {
        parent.addView(label(caption, 12f, navy, true), margins(ViewGroup.LayoutParams.WRAP_CONTENT, top = 8, bottom = 4))
        val input = EditText(this).apply {
            setText(value)
            hint = exampleHint(key, caption)
            textSize = 16f
            setTextColor(Color.rgb(21, 28, 41))
            setPadding(dp(13), dp(8), dp(10), dp(8))
            background = rounded(Color.WHITE, 9f, Color.rgb(164, 190, 232))
            minHeight = dp(49)
            maxLines = 1
            isSingleLine = true
            isFocusable = false
            isCursorVisible = false
            isClickable = true
            setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_expand_more, 0)
            compoundDrawablePadding = dp(7)
            contentDescription = "$caption. Chạm để chọn"
            setOnClickListener { press(this); chooser() }
        }
        fields[key] = input
        parent.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        parent.addView(label("Chạm để chọn, không cần nhập bàn phím", 10f, Color.rgb(101, 117, 143), false), margins(ViewGroup.LayoutParams.WRAP_CONTENT, top = 3))
    }

    private fun exampleHint(key: String, caption: String): String = when {
        key.endsWith(".name") -> "Ví dụ: Nguyễn Văn An"
        key.endsWith(".birthDate") -> "Ví dụ: 14/03/1996"
        key.endsWith(".citizenId") -> "Ví dụ: 079096001234"
        key.endsWith(".issueDate") -> "Ví dụ: 20/10/2024"
        key.endsWith(".issuePlace") -> "Ví dụ: Bộ Công an"
        key.endsWith(".permanentAddress") -> "Ví dụ: Số nhà, đường, phường/xã, tỉnh/thành"
        key.endsWith(".currentAddress") -> "Ví dụ: Địa chỉ nơi đang ở"
        key == "time" -> "Ví dụ: 20 giờ 00 phút"
        key == "date" -> "Ví dụ: 25 tháng 09 năm 2026"
        key == "place" -> "Ví dụ: Phường Bình Quới, Thành phố Hồ Chí Minh"
        key == "area" -> "Ví dụ: 30 m²"
        key == "duration" -> "Ví dụ: 02 năm"
        key == "monthlyRent" -> "Ví dụ: 2.300.000 VNĐ/tháng"
        else -> "Nhập $caption"
    }

    private fun showTimePicker(target: EditText) {
        val values = Regex("(\\d{1,2}).*?(\\d{1,2})").find(target.text.toString())?.groupValues
        val hour = values?.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 23) ?: 20
        val minute = values?.getOrNull(2)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
        TimePickerDialog(this, { _, selectedHour, selectedMinute ->
            target.setText(String.format(Locale("vi", "VN"), "%02d giờ %02d phút", selectedHour, selectedMinute))
        }, hour, minute, true).show()
    }

    private fun showDatePicker(target: EditText) {
        val numbers = Regex("\\d+").findAll(target.text.toString()).map { it.value.toInt() }.toList()
        val now = Calendar.getInstance()
        val day = numbers.getOrNull(0)?.coerceIn(1, 31) ?: now.get(Calendar.DAY_OF_MONTH)
        val month = numbers.getOrNull(1)?.coerceIn(1, 12)?.minus(1) ?: now.get(Calendar.MONTH)
        val year = numbers.getOrNull(2)?.coerceIn(1900, 2200) ?: now.get(Calendar.YEAR)
        DatePickerDialog(this, { _, selectedYear, selectedMonth, selectedDay ->
            target.setText(String.format(Locale("vi", "VN"), "%02d tháng %02d năm %04d", selectedDay, selectedMonth + 1, selectedYear))
        }, year, month, day).show()
    }

    private fun showAreaPicker(target: EditText) {
        val picker = NumberPicker(this).apply {
            minValue = 5
            maxValue = 500
            value = Regex("\\d+").find(target.text.toString())?.value?.toIntOrNull()?.coerceIn(5, 500) ?: 30
            wrapSelectorWheel = false
            setFormatter { "$it m²" }
        }
        AlertDialog.Builder(this)
            .setTitle("Chọn diện tích thuê")
            .setView(picker)
            .setPositiveButton("Áp dụng") { _, _ -> target.setText("${picker.value} m²") }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun showDurationPicker(target: EditText) {
        val values = arrayOf("01 tháng", "03 tháng", "06 tháng", "09 tháng", "12 tháng", "18 tháng", "24 tháng (02 năm)")
        val current = values.indexOf(target.text.toString()).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Chọn thời hạn thuê")
            .setSingleChoiceItems(values, current) { dialog, which ->
                target.setText(values[which])
                dialog.dismiss()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun showRentPicker(target: EditText) {
        val picker = NumberPicker(this).apply {
            minValue = 5
            maxValue = 500
            val currentAmount = target.text.toString().filter(Char::isDigit).toLongOrNull() ?: 2_300_000L
            value = (currentAmount / 100_000L).toInt().coerceIn(minValue, maxValue)
            wrapSelectorWheel = false
            setFormatter { formatRent(it * 100_000L) }
        }
        AlertDialog.Builder(this)
            .setTitle("Chọn giá thuê mỗi tháng")
            .setMessage("Vuốt để tăng hoặc giảm theo bước 100.000 đồng")
            .setView(picker)
            .setPositiveButton("Áp dụng") { _, _ -> target.setText("${formatRent(picker.value * 100_000L)} VNĐ/tháng") }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun formatRent(amount: Long): String = String.format(Locale.US, "%,d", amount).replace(',', '.')

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
        contract.placeDetail = value("placeDetail", contract.placeDetail)
        contract.ward = value("contractWard", contract.ward)
        contract.province = value("contractProvince", contract.province)
        contract.place = composeAddress(contract.placeDetail, contract.ward, contract.province).ifBlank { value("place", contract.place) }
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
        showAppChrome()
        collectForm()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(8))
            setBackgroundColor(Color.rgb(218, 226, 239))
        }
        val toolbar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), dp(7), dp(7), dp(7))
            background = rounded(Color.WHITE, 14f, Color.rgb(194, 208, 230))
        }
        val instructions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        instructions.addView(label("Bản xem trước · 02 trang A4", 13f, deepBlue, true))
        instructions.addView(label("Chụm 2 ngón để phóng to · kéo để di chuyển", 10f, Color.rgb(93, 108, 133), false), margins(ViewGroup.LayoutParams.WRAP_CONTENT, top = 2))
        toolbar.addView(instructions, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        toolbar.addView(actionButton("Chỉnh sửa", Color.rgb(232, 240, 255), blue) { showForm() }, LinearLayout.LayoutParams(dp(92), dp(40)))
        container.addView(toolbar, margins(ViewGroup.LayoutParams.WRAP_CONTENT, bottom = 9))

        val previewFrame = FrameLayout(this).apply {
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = rounded(Color.rgb(202, 213, 231), 9f)
        }
        val image = ZoomableImageView(this).apply {
            setImageBitmap(ContractRenderer.renderBitmap(this@MainActivity, contract, tenantSignature, landlordSignature, 2))
            background = rounded(Color.rgb(234, 239, 247), 5f)
            contentDescription = "Bản xem trước hai trang hợp đồng thuê nhà. Có thể phóng to và thu nhỏ bằng hai ngón tay."
        }
        previewFrame.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        container.addView(previewFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        swapContent(container)
    }

    private fun showSignatures() {
        showAppChrome()
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
        if (!validateContractForExport()) return
        runCatching {
            val file = ContractRenderer.createPdf(this, contract, tenantSignature, landlordSignature)
            val uri = fileUri(file)
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
            publishToDownloads(file, "application/pdf")
            toast("Đã tạo PDF khổ A4 trong thư mục Tải xuống")
        }.onFailure {
            if (it is ActivityNotFoundException) toast("Đã tạo PDF nhưng thiết bị chưa có ứng dụng đọc PDF")
            else toast("Không thể tạo PDF: ${it.message ?: "lỗi không xác định"}")
        }
    }

    private fun sharePdf() {
        collectForm()
        if (!validateContractForExport()) return
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
        if (!validateContractForExport()) return
        toast("Đang ghép 02 trang A4 vào một hình…")
        Thread {
            runCatching {
                val directory = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir, "HopDong")
                check(directory.exists() || directory.mkdirs()) { "Không thể tạo thư mục ảnh" }
                val file = File(directory, "Hop_Dong_${safeFileStamp()}_02_Trang_A4.png")
                val bitmap = ContractRenderer.renderCombinedA4Bitmap(this, contract, tenantSignature, landlordSignature)
                FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
                bitmap.recycle()
                MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf("image/png"), null)
                publishToDownloads(file, "image/png")
                file
            }.onSuccess {
                runOnUiThread { toast("Đã lưu 02 trang A4 chung trong 01 hình vào thư mục Tải xuống/HopDong") }
            }.onFailure {
                runOnUiThread { toast("Không thể lưu ảnh: ${it.message ?: "lỗi không xác định"}") }
            }
        }.start()
    }

    private fun validateContractForExport(): Boolean {
        fun reject(message: String): Boolean { toast(message); return false }
        if (contract.time.isBlank() || contract.date.isBlank() || contract.place.isBlank()) return reject("Hợp đồng còn thiếu thời gian, ngày hoặc địa điểm lập")
        if (contract.landlord.name.isBlank() || !contract.landlord.citizenId.matches(Regex("\\d{12}"))) return reject("Thông tin Bên A chưa đầy đủ hoặc CCCD chưa đúng 12 số")
        if (contract.tenant.name.isBlank() || !contract.tenant.citizenId.matches(Regex("\\d{12}"))) return reject("Thông tin Bên B chưa đầy đủ hoặc CCCD chưa đúng 12 số")
        if (contract.area.isBlank() || contract.duration.isBlank() || contract.monthlyRent.isBlank()) return reject("Hợp đồng còn thiếu diện tích, thời hạn hoặc giá thuê")
        return true
    }

    private fun publishToDownloads(source: File, mimeType: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            val folder = if (source.name.contains("CT01", ignoreCase = true) || source.parentFile?.name.equals("CT01", ignoreCase = true)) "CT01" else "HopDong"
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/$folder")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val target = contentResolver.insert(collection, values) ?: return
        try {
            contentResolver.openOutputStream(target)?.use { output -> source.inputStream().use { it.copyTo(output) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(target, values, null, null)
        } catch (error: Exception) {
            contentResolver.delete(target, null, null)
            throw error
        }
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
                fields.clear()
                showForm()
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

    private fun analyzeGalleryQr(uri: Uri) {
        if (pendingGalleryScan) return
        pendingGalleryScan = true
        toast("Đang phân tích ảnh QR nhiều lớp…")
        cameraExecutor.execute {
            val bitmap = runCatching { decodeGalleryBitmap(uri) }.getOrNull()
            if (bitmap == null) {
                pendingGalleryScan = false
                runOnUiThread { toast("Không thể đọc ảnh đã chọn") }
                return@execute
            }
            scanGalleryVariant(GalleryScanState(bitmap), 0)
        }
    }

    private fun scanGalleryVariant(state: GalleryScanState, attempt: Int) {
        if (attempt >= 8) {
            state.original.recycle()
            pendingGalleryScan = false
            runOnUiThread { toast("Không nhận diện được QR CCCD. Hãy chọn ảnh rõ hơn hoặc cắt ảnh gần mã QR.") }
            return
        }

        val candidate = createGalleryCandidate(state, attempt)
        val image = InputImage.fromBitmap(candidate.bitmap, candidate.rotation)
        galleryBarcodeScanner.process(image)
            .addOnSuccessListener { codes ->
                val decoded = codes.firstOrNull {
                    !it.rawValue.isNullOrBlank() && it.rawValue.orEmpty().count { char -> char == '|' } >= 5
                }?.rawValue
                if (attempt == 0 && state.potentialArea == null) {
                    state.potentialArea = codes.mapNotNull { it.boundingBox }
                        .maxByOrNull { it.width().toLong() * it.height().toLong() }
                }
                if (!decoded.isNullOrBlank()) {
                    if (candidate.recycleAfterUse && !candidate.bitmap.isRecycled) candidate.bitmap.recycle()
                    state.original.recycle()
                    pendingGalleryScan = false
                    acceptQr(decoded)
                } else {
                    if (candidate.recycleAfterUse && !candidate.bitmap.isRecycled) candidate.bitmap.recycle()
                    scanGalleryVariant(state, attempt + 1)
                }
            }
            .addOnFailureListener {
                if (candidate.recycleAfterUse && !candidate.bitmap.isRecycled) candidate.bitmap.recycle()
                scanGalleryVariant(state, attempt + 1)
            }
    }

    private fun createGalleryCandidate(state: GalleryScanState, attempt: Int): GalleryCandidate = when (attempt) {
        0 -> GalleryCandidate(state.original, 0, false)
        1 -> state.potentialArea?.let { area ->
            val cropped = cropPotentialQr(state.original, area)
            GalleryCandidate(cropped, 0, cropped !== state.original)
        } ?: GalleryCandidate(enhanceQrImage(state.original), 0, true)
        2 -> GalleryCandidate(state.original, 90, false)
        3 -> GalleryCandidate(state.original, 180, false)
        4 -> GalleryCandidate(state.original, 270, false)
        5 -> GalleryCandidate(enhanceQrImage(state.original), 0, true)
        6 -> GalleryCandidate(enhanceQrImage(state.original), 90, true)
        else -> GalleryCandidate(enhanceQrImage(state.original), 270, true)
    }

    private fun decodeGalleryBitmap(uri: Uri): Bitmap {
        val maxSide = 4096
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val width = info.size.width
                val height = info.size.height
                val longest = maxOf(width, height)
                if (longest > maxSide) {
                    val scale = maxSide.toFloat() / longest
                    decoder.setTargetSize((width * scale).toInt(), (height * scale).toInt())
                }
            }
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: error("Ảnh không hợp lệ")
    }

    private fun cropPotentialQr(source: Bitmap, detected: Rect): Bitmap {
        val padding = maxOf(detected.width(), detected.height()) / 2
        val left = (detected.left - padding).coerceIn(0, source.width - 1)
        val top = (detected.top - padding).coerceIn(0, source.height - 1)
        val right = (detected.right + padding).coerceIn(left + 1, source.width)
        val bottom = (detected.bottom + padding).coerceIn(top + 1, source.height)
        val crop = Bitmap.createBitmap(source, left, top, right - left, bottom - top)
        val longest = maxOf(crop.width, crop.height)
        if (longest >= 1600) return crop
        val factor = (1600f / longest).coerceAtMost(3f)
        val scaled = Bitmap.createScaledBitmap(crop, (crop.width * factor).toInt(), (crop.height * factor).toInt(), true)
        if (scaled !== crop) crop.recycle()
        return scaled
    }

    private fun enhanceQrImage(source: Bitmap): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.RGB_565)
        val grayscale = ColorMatrix().apply { setSaturation(0f) }
        val contrast = 1.65f
        val offset = (-0.5f * contrast + 0.5f) * 255f
        grayscale.postConcat(ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, offset,
            0f, contrast, 0f, 0f, offset,
            0f, 0f, contrast, 0f, offset,
            0f, 0f, 0f, 1f, 0f
        )))
        Canvas(output).drawBitmap(source, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(grayscale)
            isFilterBitmap = true
        })
        return output
    }

    private fun startCameraScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) showCameraScanner()
        else requestCamera.launch(Manifest.permission.CAMERA)
    }

    private fun showCameraScanner() {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(5, 12, 26)) }
        val previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        }
        previewView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) focusAt(previewView, event.x, event.y)
            true
        }
        root.addView(previewView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        scannerOverlay = QrScannerOverlayView(this)
        root.addView(scannerOverlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val overlay = TextView(this).apply {
            text = "Đang tự tìm và lấy nét mã QR\nGiữ CCCD ổn định trong vùng quét"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = GradientDrawable().apply { setColor(Color.argb(185, 9, 22, 45)); cornerRadius = dp(15).toFloat() }
        }
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply { setMargins(dp(18), dp(42), dp(18), 0) })
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        controls.addView(actionButton("Ảnh QR", Color.argb(210, 29, 43, 67)) { dialog.dismiss(); pickQrImage.launch("image/*") }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(6) })
        controls.addView(actionButton("Bật đèn", Color.argb(210, 29, 43, 67)) {
            val camera = boundCamera
            val enable = camera?.cameraInfo?.torchState?.value != 1
            camera?.cameraControl?.enableTorch(enable)
        }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(6); marginEnd = dp(6) })
        controls.addView(actionButton("Đóng", red) { pendingCt01ScanTarget = null; dialog.dismiss() }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(6) })
        val scannerCopyright = label("🛡  Bản quyền: trandoconghuy@gmail.com", 10f, Color.rgb(218, 226, 241), false).apply {
            gravity = Gravity.CENTER
            background = rounded(Color.argb(165, 9, 22, 45), 10f)
        }
        root.addView(scannerCopyright, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28), Gravity.BOTTOM).apply {
            setMargins(dp(32), 0, dp(32), dp(92))
        })
        root.addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58), Gravity.BOTTOM).apply { setMargins(dp(18), 0, dp(18), dp(30)) })
        dialog.setContentView(root)
        dialog.setOnDismissListener {
            mainHandler.removeCallbacksAndMessages(null)
            cameraProvider?.unbindAll()
            boundCamera = null
            scannerOverlay = null
            frameInFlight.set(false)
            scannerDialog = null
        }
        dialog.show()
        scannerDialog = dialog
        bindCamera(previewView)
    }

    private fun bindCamera(previewView: PreviewView) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(Size(1280, 720))
                .build()
            var accepted = false
            analyzer.setAnalyzer(cameraExecutor) { proxy ->
                if (accepted) { proxy.close(); return@setAnalyzer }
                scanProxy(proxy, previewView) { raw ->
                    if (!accepted) {
                        accepted = true
                        runOnUiThread {
                            scannerOverlay?.showSuccess()
                            previewView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70).startTone(ToneGenerator.TONE_PROP_ACK, 120) }
                            mainHandler.postDelayed({ scannerDialog?.dismiss(); acceptQr(raw) }, 260)
                        }
                    }
                }
            }
            runCatching {
                cameraProvider?.unbindAll()
                boundCamera = cameraProvider?.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analyzer)
                previewView.postDelayed({ focusAt(previewView, previewView.width / 2f, previewView.height / 2f) }, 350)
                scheduleContinuousFocus(previewView)
            }.onFailure { toast("Không thể khởi động camera") }
        }, ContextCompat.getMainExecutor(this))
    }

    @ExperimentalGetImage
    private fun scanProxy(proxy: ImageProxy, previewView: PreviewView, onFound: (String) -> Unit) {
        if (!frameInFlight.compareAndSet(false, true)) { proxy.close(); return }
        val media = proxy.image
        if (media == null) { frameInFlight.set(false); proxy.close(); return }
        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        barcodeScanner.process(image)
            .addOnSuccessListener { codes ->
                val qr = codes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }
                qr?.boundingBox?.let { box ->
                    runOnUiThread {
                        scannerOverlay?.track(box, image.width, image.height, proxy.imageInfo.rotationDegrees)
                        autoZoomFor(box.width(), box.height(), image.width, image.height)
                    }
                }
                qr?.rawValue?.takeIf { it.isNotBlank() && it.count { char -> char == '|' } >= 5 }?.let(onFound)
            }
            .addOnFailureListener { scannerOverlay?.showSearching() }
            .addOnCompleteListener { frameInFlight.set(false); proxy.close() }
    }

    private fun focusAt(previewView: PreviewView, x: Float, y: Float) {
        val point = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        boundCamera?.cameraControl?.startFocusAndMetering(action)
        scannerOverlay?.pulseFocus(x, y)
    }

    private fun scheduleContinuousFocus(previewView: PreviewView) {
        if (scannerDialog == null) return
        mainHandler.postDelayed({
            if (scannerDialog != null && previewView.width > 0) {
                focusAt(previewView, previewView.width / 2f, previewView.height / 2f)
                scheduleContinuousFocus(previewView)
            }
        }, 2200)
    }

    private fun autoZoomFor(boxWidth: Int, boxHeight: Int, imageWidth: Int, imageHeight: Int) {
        val coverage = maxOf(boxWidth.toFloat() / imageWidth, boxHeight.toFloat() / imageHeight)
        if (coverage <= 0f || coverage >= .32f) return
        val state = boundCamera?.cameraInfo?.zoomState?.value ?: return
        val desired = (state.zoomRatio * (.32f / coverage)).coerceIn(state.minZoomRatio, minOf(state.maxZoomRatio, 3.2f))
        if (desired > state.zoomRatio * 1.08f) boundCamera?.cameraControl?.setZoomRatio(desired)
    }

    private fun acceptQr(raw: String) {
        val parts = raw.split('|')
        if (parts.size < 6) {
            toast("Mã QR không đúng định dạng CCCD")
            return
        }
        val citizen = ScannedCitizen(
            citizenId = parts[0].trim(),
            oldId = parts[1].trim().ifBlank { "Không có" },
            name = parts[2].trim(),
            birthDate = formatQrDate(parts[3].trim()),
            gender = parts[4].trim().ifBlank { "Không xác định" },
            permanentAddress = parts[5].trim(),
            issueDate = if (parts.size > 6) formatQrDate(parts[6].trim()) else "Không xác định"
        )
        when (pendingCt01ScanTarget.also { pendingCt01ScanTarget = null }) {
            "subject" -> {
                applyCitizenToCt01Subject(citizen)
                showCt01Form()
                toast("Đã điền thông tin người được làm thủ tục")
            }
            "member" -> {
                if (ct01Data.members.any { it.citizenId == citizen.citizenId }) {
                    toast("Thành viên này đã có trong danh sách")
                } else if (ct01Data.members.size >= 9) {
                    toast("Mẫu CT01 chỉ có tối đa 09 dòng thành viên")
                } else {
                    ct01Data.members.add(Ct01Member(vietnameseTitleCase(citizen.name), citizen.birthDate, citizen.gender, citizen.citizenId, defaultMemberRelationship(), ""))
                    showCt01MemberRelationship(ct01Data.members.lastIndex)
                }
            }
            "head" -> {
                ct01Data.headName = vietnameseTitleCase(citizen.name)
                ct01Data.headCitizenId = citizen.citizenId
                if (ct01Data.headIsLegalOwner) {
                    ct01Data.legalOwnerName = ct01Data.headName
                    ct01Data.legalOwnerCitizenId = ct01Data.headCitizenId
                }
                showCt01Form(); toast("Đã điền thông tin chủ hộ")
            }
            "owner" -> {
                ct01Data.legalOwnerName = vietnameseTitleCase(citizen.name)
                ct01Data.legalOwnerCitizenId = citizen.citizenId
                showCt01Form(); toast("Đã điền thông tin chủ sở hữu")
            }
            "guardian" -> {
                ct01Data.guardianName = vietnameseTitleCase(citizen.name)
                ct01Data.guardianCitizenId = citizen.citizenId
                showCt01Form(); toast("Đã điền thông tin người giám hộ")
            }
            "contract_landlord" -> {
                contract.landlord = PersonData(vietnameseTitleCase(citizen.name), citizen.birthDate, citizen.citizenId, citizen.issueDate, "Bộ Công an", citizen.permanentAddress, "", citizen.gender)
                showForm(); toast("Đã điền thông tin bên cho thuê")
            }
            "contract_tenant" -> {
                applyCitizenToContract(citizen); showForm(); toast("Đã điền thông tin bên thuê")
            }
            else -> showIdentityResult(citizen)
        }
    }

    private fun showIdentityResult(citizen: ScannedCitizen) {
        contractWizardVisible = false
        lastScannedCitizen = citizen
        identityResultVisible = true
        templateSelectionVisible = false
        ct01Visible = false
        topBar.visibility = View.GONE
        bottomNavigation.visibility = View.GONE

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.rgb(248, 248, 255), Color.rgb(242, 243, 252)))
        }

        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), 0, dp(18), 0)
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.rgb(9, 102, 247), Color.rgb(82, 178, 246))).apply {
                cornerRadius = dp(26).toFloat()
            }
            elevation = dp(2).toFloat()
        }
        header.addView(label("Thông Tin Căn Cước", 23f, Color.WHITE, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val verified = TextView(this).apply {
            text = "✓ Đã xác thực"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(14, 177, 91))
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0)
            background = GradientDrawable().apply { setColor(Color.rgb(225, 253, 239)); cornerRadius = dp(22).toFloat() }
        }
        header.addView(verified, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)))
        page.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(87)))

        val infoCard = MaterialCardView(this).apply {
            radius = dp(25).toFloat()
            cardElevation = dp(7).toFloat()
            setCardBackgroundColor(Color.WHITE)
            strokeWidth = 0
        }
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(29), dp(26), dp(29), dp(27))
        }
        info.addView(identityItem("SỐ CCCD", citizen.citizenId, true))
        info.addView(View(this).apply { setBackgroundColor(Color.rgb(233, 235, 241)) }, margins(1, top = 21, bottom = 18))
        info.addView(identityItem("HỌ VÀ TÊN", vietnameseTitleCase(citizen.name), false))
        info.addView(identityPair("GIỚI TÍNH", citizen.gender, "NGÀY SINH", citizen.birthDate), margins(ViewGroup.LayoutParams.WRAP_CONTENT, top = 18))
        info.addView(identityPair("NGÀY CẤP", citizen.issueDate, "CMND CŨ", citizen.oldId), margins(ViewGroup.LayoutParams.WRAP_CONTENT, top = 18))
        info.addView(identityItem("NƠI THƯỜNG TRÚ", citizen.permanentAddress, false), margins(ViewGroup.LayoutParams.WRAP_CONTENT, top = 19))
        infoCard.addView(info)
        page.addView(infoCard, margins(ViewGroup.LayoutParams.WRAP_CONTENT, top = 20, bottom = 13))

        val scanAgain = actionButton("↻  QUÉT MÃ KHÁC", Color.rgb(18, 105, 240)) { startCameraScan() }.apply {
            textSize = 15f
            letterSpacing = .08f
            cornerRadius = dp(28)
        }
        page.addView(scanAgain, margins(dp(58), bottom = 10))

        val chooseTemplate = actionButton("▦  CHỌN BIỂU MẪU", green) {
            showTemplateSelection(citizen)
        }.apply {
            textSize = 15f
            letterSpacing = .06f
            cornerRadius = dp(28)
        }
        page.addView(chooseTemplate, margins(dp(58), bottom = 8))

        val scroll = ScrollView(this).apply { isFillViewport = true; addView(page) }
        swapContent(scroll)
    }

    private fun showTemplateSelection(citizen: ScannedCitizen) {
        contractWizardVisible = false
        identityResultVisible = false
        templateSelectionVisible = true
        ct01Visible = false
        topBar.visibility = View.GONE
        bottomNavigation.visibility = View.GONE

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(24))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.rgb(247, 244, 238), Color.rgb(238, 232, 222)))
        }

        val header = MaterialCardView(this).apply {
            radius = dp(22).toFloat()
            cardElevation = dp(7).toFloat()
            strokeWidth = 0
            setCardBackgroundColor(Color.rgb(28, 29, 31))
        }
        val headerBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(17))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(24, 25, 28), Color.rgb(61, 49, 37))).apply {
                cornerRadius = dp(22).toFloat()
            }
        }
        val headerRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        headerRow.addView(ImageView(this).apply {
            setImageResource(R.drawable.app_icon); setPadding(dp(5), dp(5), dp(5), dp(5)); background = rounded(Color.WHITE, 13f)
            contentDescription = "Logo Phần mềm chuyển đổi văn bản số"
        }, LinearLayout.LayoutParams(dp(50), dp(50)).apply { marginEnd = dp(12) })
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(label("PHẦN MỀM CHUYỂN ĐỔI VĂN BẢN SỐ", 9f, Color.rgb(232, 164, 72), true).apply { letterSpacing = .08f })
        titles.addView(label("Chọn biểu mẫu", 22f, Color.WHITE, true), margins(ViewGroup.LayoutParams.WRAP_CONTENT, top = 3))
        headerRow.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        headerBody.addView(headerRow)
        headerBody.addView(label("Chọn đúng công việc cần thực hiện. Dữ liệu được xử lý trực tiếp và riêng tư trên thiết bị.", 12f, Color.rgb(222, 228, 239), false).apply {
            setLineSpacing(dp(2).toFloat(), 1f)
        }, margins(ViewGroup.LayoutParams.WRAP_CONTENT, top = 10))
        if (citizen.citizenId.isNotBlank()) headerBody.addView(label("✓  Đã nhận diện: ${citizen.name}", 11f, Color.rgb(183, 225, 195), true), margins(ViewGroup.LayoutParams.WRAP_CONTENT, top = 10))
        header.addView(headerBody)
        page.addView(header, margins(ViewGroup.LayoutParams.WRAP_CONTENT, bottom = 21))

        page.addView(templateGroupTitle("01", "Đăng ký tạm trú", "02 biểu mẫu"), margins(ViewGroup.LayoutParams.WRAP_CONTENT, bottom = 10))
        page.addView(templateCard(
            code = "HĐ",
            title = "Hợp đồng thuê nhà",
            note = "Dành cho đăng ký tạm trú",
            status = "Sẵn sàng",
            actionLabel = "Sử dụng biểu mẫu",
            accent = Color.rgb(198, 123, 37)
        ) {
            contractStep = 0
            prepareContract(citizen)
            showForm()
            if (citizen.citizenId.isNotBlank()) toast("Đã đưa thông tin CCCD vào Hợp đồng thuê nhà")
        }, margins(ViewGroup.LayoutParams.WRAP_CONTENT, bottom = 12))

        page.addView(templateCard(
            code = "CT01",
            title = "CT01",
            note = "Tờ khai thay đổi thông tin cư trú · Mẫu 116/2026",
            status = "Cập nhật 29/06/2026",
            actionLabel = "Chọn CT01",
            accent = Color.rgb(198, 123, 37)
        ) {
            prepareCt01(citizen)
            showCt01Form()
        }, margins(ViewGroup.LayoutParams.WRAP_CONTENT, bottom = 24))

        page.addView(templateGroupTitle("02", "Đăng ký hộ chiếu", "Chưa có biểu mẫu"), margins(ViewGroup.LayoutParams.WRAP_CONTENT, bottom = 10))
        val empty = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(22), dp(18), dp(22))
            background = rounded(Color.rgb(243, 239, 232), 14f, Color.rgb(215, 204, 188))
            addView(label("Chưa có biểu mẫu trong nhóm này", 13f, Color.rgb(66, 61, 55), true).apply { gravity = Gravity.CENTER })
            addView(label("Biểu mẫu mới sẽ được bổ sung đúng theo từng thủ tục.", 11f, Color.rgb(114, 105, 94), false).apply { gravity = Gravity.CENTER }, margins(ViewGroup.LayoutParams.WRAP_CONTENT, top = 4))
        }
        page.addView(empty, margins(ViewGroup.LayoutParams.WRAP_CONTENT, bottom = 12))

        swapContent(ScrollView(this).apply { isFillViewport = true; addView(page) })
    }

    private fun templateGroupTitle(number: String, title: String, count: String): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        val index = TextView(this@MainActivity).apply {
            text = number
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(198, 123, 37), 9f)
        }
        addView(index, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(10) })
        addView(label(title, 17f, navy, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(label(count, 11f, Color.rgb(101, 116, 140), false))
    }

    private fun templateCard(
        code: String,
        title: String,
        note: String,
        status: String,
        actionLabel: String,
        accent: Int,
        action: () -> Unit
    ): View {
        val card = MaterialCardView(this).apply {
            radius = dp(17).toFloat()
            cardElevation = dp(3).toFloat()
            strokeColor = Color.rgb(218, 207, 191)
            strokeWidth = dp(1)
            setCardBackgroundColor(Color.WHITE)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(15), dp(15), dp(14))
        }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val mark = TextView(this).apply {
            text = code
            textSize = if (code.length > 2) 11f else 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accent)
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(250, 241, 226), 13f, Color.rgb(225, 190, 143))
        }
        row.addView(mark, LinearLayout.LayoutParams(dp(54), dp(54)).apply { marginEnd = dp(12) })
        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(label(title, 16f, navy, true))
        copy.addView(label(note, 12f, Color.rgb(91, 106, 131), false), margins(ViewGroup.LayoutParams.WRAP_CONTENT, top = 3))
        copy.addView(label("●  $status", 10f, accent, true), margins(ViewGroup.LayoutParams.WRAP_CONTENT, top = 5))
        row.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        body.addView(row)
        body.addView(actionButton(actionLabel, Color.rgb(31, 34, 39)) { action() }.apply {
            textSize = 13f
            cornerRadius = dp(11)
        }, margins(dp(46), top = 13))
        card.addView(body)
        return card
    }

    private fun applyCitizenToContract(citizen: ScannedCitizen) {
        if (citizen.citizenId.isBlank()) return
        contract.tenant.citizenId = citizen.citizenId
        contract.tenant.name = citizen.name.uppercase(Locale("vi", "VN"))
        contract.tenant.birthDate = citizen.birthDate
        contract.tenant.permanentAddress = citizen.permanentAddress
        contract.tenant.issueDate = citizen.issueDate
        contract.tenant.issuePlace = "Bộ Công an"
        contract.tenant.gender = citizen.gender
        if (contract.tenant.currentAddress.isBlank()) contract.tenant.currentAddress = contract.place
        fields.clear()
    }

    private fun prepareContract(citizen: ScannedCitizen) {
        contract.time = java.text.SimpleDateFormat("HH 'giờ' mm 'phút'", Locale("vi", "VN")).format(Date())
        contract.date = java.text.SimpleDateFormat("dd 'tháng' MM 'năm' yyyy", Locale("vi", "VN")).format(Date())
        contractLocationRequested = false
        applyCitizenToContract(citizen)
    }

    private fun emptyCitizen() = ScannedCitizen("", "", "", "", "", "", "")

    private fun prepareCt01(citizen: ScannedCitizen) {
        ct01Data = Ct01Data(
            declarantName = vietnameseTitleCase(citizen.name),
            birthDate = citizen.birthDate,
            gender = citizen.gender.ifBlank { "Nam" },
            citizenId = citizen.citizenId,
            oldPermanentAddress = citizen.permanentAddress,
            requiresGuardian = runCatching {
                val birth = java.text.SimpleDateFormat("dd/MM/yyyy", Locale.US).apply { isLenient = false }.parse(citizen.birthDate)
                val birthday = Calendar.getInstance().apply { time = birth!! }; val today = Calendar.getInstance()
                var age = today.get(Calendar.YEAR) - birthday.get(Calendar.YEAR)
                if (today.get(Calendar.DAY_OF_YEAR) < birthday.get(Calendar.DAY_OF_YEAR)) age--
                age < 18
            }.getOrDefault(false),
            signingDate = java.text.SimpleDateFormat("dd/MM/yyyy", Locale("vi", "VN")).format(java.util.Date())
        )
        ct01Fields.clear()
        ct01DeclarantSignature = null
        ct01OwnerSignature = null
        ct01HeadSignature = null
        ct01GuardianSignature = null
        ct01Step = 0
        ct01LocationRequested = false
    }

    private fun showCt01Form() {
        showCt01Wizard()
    }

    private fun showCt01Wizard() {
        contractWizardVisible = false
        identityResultVisible = false; templateSelectionVisible = false; ct01Visible = true
        topBar.visibility = View.GONE; bottomNavigation.visibility = View.GONE
        ct01Fields.clear(); ct01ActiveSignatureView = null; ct01ActiveSignatureTarget = null

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(246, 245, 241)) }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(11), dp(14), dp(11)); setBackgroundColor(Color.rgb(22, 35, 63))
        }
        header.addView(TextView(this).apply {
            text = "CA"; gravity = Gravity.CENTER; textSize = 11f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(22, 35, 63)); background = rounded(Color.rgb(184, 134, 46), 18f)
        }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(11) })
        val title = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title.addView(label("Tờ khai CT01 — Đăng ký tạm trú", 15f, Color.WHITE, true))
        title.addView(label("Bước ${ct01Step + 1} / 11", 11f, Color.rgb(190, 199, 218), false), margins(ViewGroup.LayoutParams.WRAP_CONTENT, top = 2))
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(iconButton("×", "Đóng CT01") { showTemplateSelection(lastScannedCitizen ?: emptyCitizen()) }, LinearLayout.LayoutParams(dp(40), dp(40)))
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))

        val progress = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(14), dp(10), dp(14), dp(7)) }
        repeat(11) { index ->
            progress.addView(View(this).apply { background = rounded(if (index <= ct01Step) Color.rgb(184, 134, 46) else Color.rgb(227, 225, 218), 3f) },
                LinearLayout.LayoutParams(0, dp(4), 1f).apply { if (index < 10) marginEnd = dp(3) })
        }
        root.addView(progress)

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(24)) }
        buildCt01WizardStep(body)
        if (ct01Step == 9) root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        else root.addView(ScrollView(this).apply { isFillViewport = true; isSmoothScrollingEnabled = true; addView(body) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val navigation = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(dp(16), dp(10), dp(16), dp(14)); setBackgroundColor(Color.rgb(246, 245, 241)) }
        if (ct01Step > 0) navigation.addView(actionButton("Quay lại", Color.TRANSPARENT, navy) {
            persistCt01WizardStep(); ct01Step--; showCt01Wizard()
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
        val nextLabel = when (ct01Step) { 9 -> "Kiểm tra xong"; 10 -> "Về biểu mẫu"; else -> "Tiếp tục" }
        navigation.addView(actionButton(nextLabel, Color.rgb(22, 35, 63)) {
            persistCt01WizardStep()
            if (ct01Step == 10) showTemplateSelection(lastScannedCitizen ?: emptyCitizen())
            else if (validateCt01WizardStep()) { ct01Step++; showCt01Wizard() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = if (ct01Step > 0) dp(6) else 0 })
        root.addView(navigation)
        swapContent(root)
        if (ct01Step == 1 && ct01Data.province.isBlank() && !ct01LocationRequested) {
            ct01LocationRequested = true
            requestAutomaticLocation("ct01")
        }
    }

    private fun buildCt01WizardStep(body: LinearLayout) {
        when (ct01Step) {
            0 -> {
                wizardHeading(body, "B1 · Khởi động", "Trước khi bắt đầu", "Ba vai trò dễ nhầm trong tờ khai tạm trú.")
                wizardNote(body, "Người kê khai", "Người trực tiếp khai tờ CT01.", Color.rgb(22, 35, 63))
                wizardNote(body, "Chủ hộ", "Người đứng tên hộ tại nơi đăng ký. Nếu lập hộ tạm trú riêng, người đăng ký có thể là chủ hộ mới.", Color.rgb(184, 134, 46))
                wizardNote(body, "Chủ sở hữu nhà", "Người có quyền sở hữu hợp pháp đối với nơi ở; có thể trùng hoặc khác chủ hộ.", Color.rgb(91, 72, 130))
                body.addView(label("Bạn làm thủ tục này cho ai?", 13f, navy, true), margins(ViewGroup.LayoutParams.WRAP_CONTENT, top = 8, bottom = 7))
                wizardSelectCard(body, "T", "Cho chính tôi", "Người trên CCCD là người được đăng ký", ct01Data.filingMode == "Cho chính tôi") { ct01Data.filingMode = "Cho chính tôi"; showCt01Wizard() }
                wizardSelectCard(body, "H", "Làm hộ người khác", "Quét CCCD của người được làm thủ tục ở bước 5", ct01Data.filingMode == "Làm hộ cho người khác") { ct01Data.filingMode = "Làm hộ cho người khác"; showCt01Wizard() }
                wizardChoice(body, "Chủ hộ đồng thời là chủ sở hữu nhà", "App tự xác định thông tin dùng chung và những mục phải để trống trên tờ khai", ct01Data.headIsLegalOwner) {
                    ct01Data.headIsLegalOwner = !ct01Data.headIsLegalOwner; showCt01Wizard()
                }
            }
            1 -> {
                wizardHeading(body, "B2 · Nơi nộp hồ sơ", "Bạn nộp hồ sơ tại cơ quan nào?", "Mục Kính gửi sẽ được tự đề xuất từ phường/xã ở bước 4 và vẫn có thể chỉnh lại.")
                body.addView(label("Danh mục hành chính · chọn nhanh", 11f, green, true), margins(ViewGroup.LayoutParams.WRAP_CONTENT, bottom = 8))
                ct01SmartField(body, "province", "Tỉnh / Thành phố", ct01Data.province) {
                    showCt01ListPicker("Chọn tỉnh / thành phố", provinceChoices()) { collectCt01Form(); ct01Data.province = it; ct01Data.ward = ""; ct01Data.authority = ""; showCt01Wizard() }
                }
                ct01SmartField(body, "ward", "Phường / Xã nơi đăng ký", ct01Data.ward) {
                    if (ct01Data.province.isBlank()) toast("Vui lòng chọn tỉnh / thành phố trước")
                    else showWardPicker(ct01Data.province) { collectCt01Form(); ct01Data.ward = it; updateCt01GeneratedText(); showCt01Wizard() }
                }
                ct01Field(body, "authority", "Cơ quan đăng ký cư trú", ct01Data.authority, true)
                body.addView(actionButton("⌖  Tự động xác định theo vị trí", Color.rgb(22, 35, 63)) { collectCt01Form(); requestAutomaticLocation("ct01") }, margins(dp(46), top = 8))
            }
            2 -> {
                wizardHeading(body, "B3 · Loại hình tạm trú", "Bạn đăng ký theo diện nào?", "Lựa chọn này quyết định cách điền chủ hộ và nội dung đề nghị.")
                wizardSelectCard(body, "H", "Lập hộ tạm trú riêng", "Người đăng ký là chủ hộ của hộ tạm trú mới", ct01Data.householdType == "Lập hộ tạm trú riêng") {
                    ct01Data.householdType = "Lập hộ tạm trú riêng"; ct01Data.relationshipToHead = "Chủ hộ"; showCt01Wizard()
                }
                wizardSelectCard(body, "G", "Ở chung, nhập vào hộ người khác", "Cần thông tin và ý kiến của chủ hộ hiện tại", ct01Data.householdType == "Ở chung, nhập vào hộ người khác") {
                    ct01Data.householdType = "Ở chung, nhập vào hộ người khác"; showCt01Wizard()
                }
            }
            3 -> {
                wizardHeading(body, "B4 · Nơi ở mới", "Thông tin nơi tạm trú", "Tách đúng ba trường theo dữ liệu hành chính.")
                ct01Field(body, "newAddressDetail", "1. Số nhà, tên đường, thôn/ấp/khu phố", ct01Data.newAddressDetail, true)
                ct01SmartField(body, "ward", "2. Phường / Xã", ct01Data.ward) {
                    if (ct01Data.province.isBlank()) toast("Vui lòng chọn tỉnh / thành phố trước")
                    else showWardPicker(ct01Data.province) { collectCt01Form(); ct01Data.ward = it; updateCt01GeneratedText(); showCt01Wizard() }
                }
                ct01SmartField(body, "province", "3. Tỉnh / Thành phố", ct01Data.province) {
                    showCt01ListPicker("Chọn tỉnh / thành phố", provinceChoices()) { collectCt01Form(); ct01Data.province = it; ct01Data.ward = ""; ct01Data.authority = ""; showCt01Wizard() }
                }
                body.addView(actionButton("⌖  Dùng vị trí hiện tại", Color.WHITE, deepBlue) { collectCt01Form(); requestAutomaticLocation("ct01") }, margins(dp(46), bottom = 8))
                ct01DateField(body, "temporaryUntil", "Tạm trú đến ngày", ct01Data.temporaryUntil)
            }
            4 -> {
                wizardHeading(body, "B5 · Người kê khai", "Thông tin của bạn", "Quét QR căn cước hoặc chọn ảnh có sẵn trong máy.")
                buildCt01PartyStep(body, "subject", ct01Data.declarantName, ct01Data.citizenId,
                    "Tôi, ${ct01Data.declarantName.ifBlank { "[Họ tên]" }}, cam đoan các thông tin khai trên là đúng sự thật.", ct01Data.applicantAgreed, ct01DeclarantSignature) { section ->
                    ct01Field(section, "phone", "Số điện thoại nhận kết quả", ct01Data.phone)
                    ct01Field(section, "email", "Email nhận kết quả (không bắt buộc)", ct01Data.email)
                }
            }
            5 -> {
                wizardHeading(body, "B6 · Chủ hộ", "Thông tin chủ hộ", "Giao diện và thao tác giống bước người kê khai.")
                if (ct01Data.householdType == "Lập hộ tạm trú riêng") {
                    autoFillCt01HeadFromSubject()
                    wizardSkip(body, "Người kê khai đồng thời là chủ hộ: mục ý kiến chủ hộ trên giấy sẽ để trống, không chèn cam kết, tên hoặc chữ ký.")
                } else buildCt01PartyStep(body, "head", ct01Data.headName, ct01Data.headCitizenId,
                    "Tôi đồng ý cho ${ct01Data.declarantName.ifBlank { "người đăng ký" }} đăng ký tạm trú vào hộ của tôi tại địa chỉ trên.", ct01Data.headAgreed, ct01HeadSignature) { section ->
                    ct01SmartField(section, "relationshipToHead", "Chủ hộ là gì của người đăng ký?", ct01Data.relationshipToHead) { showRelationshipPicker() }
                }
            }
            6 -> {
                wizardHeading(body, "B7 · Chủ sở hữu nhà", "Thông tin chủ sở hữu", "Chỉ nhập riêng khi chủ hộ không đồng thời là chủ sở hữu.")
                if (ct01Data.headIsLegalOwner) {
                    autoFillCt01OwnerFromHead()
                    wizardSkip(body, if (ct01Data.declarantIsOwner()) "Người kê khai đồng thời là chủ sở hữu: mục ý kiến chủ sở hữu trên giấy sẽ để trống." else "Chủ hộ đồng thời là chủ sở hữu; thông tin được dùng chung theo lựa chọn của bạn.")
                } else buildCt01PartyStep(body, "owner", ct01Data.legalOwnerName, ct01Data.legalOwnerCitizenId,
                    buildOwnerConsent(), ct01Data.ownerAgreed, ct01OwnerSignature)
            }
            7 -> {
                wizardHeading(body, "B8 · Cha mẹ / người giám hộ", "Áp dụng khi cần", "Người dưới 18 tuổi hoặc thuộc trường hợp cần người đại diện phải bổ sung mục này.")
                wizardChoice(body, "Cần ý kiến cha/mẹ/người giám hộ", "App tự gợi ý theo ngày sinh; bạn có thể điều chỉnh", ct01Data.requiresGuardian) {
                    ct01Data.requiresGuardian = !ct01Data.requiresGuardian; showCt01Wizard()
                }
                if (!ct01Data.requiresGuardian) wizardSkip(body, "Không áp dụng — bỏ qua bước này.")
                else if (ct01Data.declarantIsGuardian()) wizardSkip(body, "Người kê khai đồng thời là cha/mẹ/người giám hộ: mục ký tương ứng trên giấy sẽ để trống.")
                else buildCt01PartyStep(body, "guardian", ct01Data.guardianName, ct01Data.guardianCitizenId,
                    "Tôi đồng ý cho ${ct01Data.declarantName.ifBlank { "người được giám hộ" }} đăng ký tạm trú theo nội dung đã khai.", ct01Data.guardianAgreed, ct01GuardianSignature)
            }
            8 -> {
                wizardHeading(body, "B9 · Thành viên cùng thay đổi", "Những người cùng chuyển đến", "Bỏ qua nếu làm một mình; mẫu CT01 cho phép tối đa 09 dòng thành viên.")
                val memberText = if (ct01Data.members.isEmpty()) "Không có thành viên đi cùng" else ct01Data.members.joinToString("\n") { "• ${it.name} · ${it.citizenId.takeLast(4)} · ${it.relationship}" }
                body.addView(label(memberText, 12f, Color.rgb(79, 88, 105), false).apply { setPadding(dp(12), dp(10), dp(12), dp(10)); background = rounded(Color.WHITE, 10f, Color.rgb(227, 225, 218)) }, margins(ViewGroup.LayoutParams.WRAP_CONTENT, bottom = 8))
                body.addView(actionButton("Quét QR CCCD thành viên", Color.rgb(22, 35, 63)) { pendingCt01ScanTarget = "member"; startCameraScan() }, margins(dp(48), bottom = 8))
                body.addView(actionButton("+ Thêm thành viên thủ công", Color.WHITE, deepBlue) { showAddCt01MemberDialog() }, margins(dp(46), bottom = 8))
                if (ct01Data.members.isNotEmpty()) body.addView(actionButton("Kiểm tra giới tính và quan hệ", Color.WHITE, green) { showCt01MemberEditor() }, margins(dp(43), bottom = 10))
                wizardSkip(body, if (ct01Data.members.isEmpty()) "Nếu làm thủ tục một mình, bạn có thể tiếp tục mà không cần thêm thành viên." else "${ct01Data.members.size} thành viên sẽ được đưa vào bảng Mục 11 đúng thứ tự.")
            }
            9 -> {
                wizardHeading(body, "B10 · Xem trước", "Đúng như bản giấy sẽ nộp", "Vùng xem trước hoạt động độc lập: chụm để phóng to, kéo để di chuyển, chạm hai lần để đặt lại.")
                body.addView(ct01SignatureStatusCard(), margins(ViewGroup.LayoutParams.WRAP_CONTENT, bottom = 10))
                body.addView(ZoomableImageView(this).apply {
                    setImageBitmap(Ct01Renderer.renderBitmap(this@MainActivity, ct01Data, ct01DeclarantSignature, ct01OwnerSignature, ct01HeadSignature, ct01GuardianSignature, 2))
                    background = rounded(Color.rgb(232, 236, 243), 8f, Color.rgb(210, 214, 222))
                    contentDescription = "Bản xem trước hai trang CT01. Có thể phóng to và di chuyển bằng cảm ứng."
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            }
            else -> {
                completionHeading(body, "B11 · Hoàn tất", "Tờ khai đã sẵn sàng", "Xuất đúng định dạng, chia sẻ hoặc mở hướng dẫn nộp trực tuyến.")
                wizardExportRow(body, "PDF", "Xuất file PDF", "Khổ A4, dùng để in và nộp trực tiếp") { createCt01Pdf() }
                wizardExportRow(body, "DOC", "Xuất file Word", "Có thể chỉnh sửa trên Microsoft Word") { createCt01Docx() }
                wizardExportRow(body, "PNG", "Xuất hình ảnh", "Ghép hai trang trong một ảnh chất lượng cao") { saveCt01Image() }
                wizardExportRow(body, "↗", "Chia sẻ PDF", "Gửi qua Zalo, email, Drive hoặc ứng dụng khác") { shareCt01Pdf() }
                wizardExportRow(body, "↗", "Hướng dẫn nộp online", "Cổng DVC Bộ Công an hoặc VNeID") { showCt01OnlineGuide() }
            }
        }
    }

    private fun wizardHeading(parent: LinearLayout, step: String, title: String, subtitle: String) {
        parent.addView(label(step.uppercase(Locale("vi", "VN")), 11f, Color.rgb(184, 134, 46), true).apply { letterSpacing = .06f })
        parent.addView(label(title, 22f, Color.rgb(28, 34, 51), true), margins(ViewGroup.LayoutParams.WRAP_CONTENT, top = 4))
        parent.addView(label(subtitle, 13f, Color.rgb(107, 114, 128), false).apply { setLineSpacing(dp(2).toFloat(), 1f) }, margins(ViewGroup.LayoutParams.WRAP_CONTENT, top = 5, bottom = 16))
    }

    private fun completionHeading(parent: LinearLayout, step: String, title: String, subtitle: String) {
        wizardHeading(parent, step, title, subtitle)
        val status = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(Color.rgb(235, 246, 239), 12f, Color.rgb(177, 211, 188))
            addView(label("✓", 18f, Color.WHITE, true).apply { gravity = Gravity.CENTER; background = rounded(green, 18f) }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(11) })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("Dữ liệu đã được kiểm tra", 13f, navy, true))
                addView(label("Chọn định dạng bên dưới để xuất hoặc chia sẻ.", 11f, Color.rgb(86, 98, 117), false), margins(ViewGroup.LayoutParams.WRAP_CONTENT, top = 2))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        parent.addView(status, margins(ViewGroup.LayoutParams.WRAP_CONTENT, bottom = 14))
    }

    private fun showRelationshipPicker() {
        val labels = arrayOf(
            "Chính tôi là chủ hộ", "Chủ hộ là vợ của tôi", "Chủ hộ là chồng của tôi",
            "Chủ hộ là cha của tôi", "Chủ hộ là mẹ của tôi", "Chủ hộ là con của tôi",
            "Tôi thuê nhà của chủ hộ", "Tôi ở nhờ nhà chủ hộ", "Quan hệ khác"
        )
        val values = arrayOf("Chủ hộ", "Chồng", "Vợ", "Con", "Con", "Cha/Mẹ", "Ở thuê", "Ở nhờ", "Khác")
        AlertDialog.Builder(this).setTitle("Chủ hộ là gì của người đăng ký?").setItems(labels) { _, index ->
            collectCt01Form(); ct01Data.relationshipToHead = values[index]; showCt01Wizard()
        }.setNegativeButton("Hủy", null).show()
    }

    private fun wizardNote(parent: LinearLayout, title: String, text: String, dotColor: Int) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP; setPadding(dp(14), dp(11), dp(14), dp(11)); background = rounded(Color.WHITE, 11f, Color.rgb(227, 225, 218)) }
        row.addView(View(this).apply { background = rounded(dotColor, 5f) }, LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(10); topMargin = dp(4) })
        row.addView(label("$title — $text", 13f, navy, false).apply { setLineSpacing(dp(2).toFloat(), 1f) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        parent.addView(row, margins(ViewGroup.LayoutParams.WRAP_CONTENT, bottom = 8))
    }

    private fun wizardChoice(parent: LinearLayout, title: String, subtitle: String, selected: Boolean, action: () -> Unit) {
        parent.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(12), dp(12), dp(12)); isClickable = true
            background = rounded(if (selected) Color.rgb(241, 228, 200) else Color.WHITE, 12f, if (selected) Color.rgb(184, 134, 46) else Color.rgb(227, 225, 218)); setOnClickListener { action() }
            val copy = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; addView(label(title, 14f, navy, true)); addView(label(subtitle, 11f, Color.rgb(107, 114, 128), false), margins(ViewGroup.LayoutParams.WRAP_CONTENT, top = 3)) }
            addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)); addView(label(if (selected) "✓" else "○", 20f, if (selected) green else Color.rgb(150, 157, 169), true))
        }, margins(ViewGroup.LayoutParams.WRAP_CONTENT, bottom = 10))
    }

    private fun wizardSelectCard(parent: LinearLayout, icon: String, title: String, subtitle: String, selected: Boolean, action: () -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(13), dp(13), dp(13), dp(13)); isClickable = true; setOnClickListener { action() }; background = rounded(if (selected) Color.rgb(241, 228, 200) else Color.WHITE, 13f, if (selected) Color.rgb(184, 134, 46) else Color.rgb(227, 225, 218)) }
        row.addView(label(icon, 14f, Color.WHITE, true).apply { gravity = Gravity.CENTER; background = rounded(Color.rgb(22, 35, 63), 9f) }, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(12) })
        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(label(title, 14f, navy, true)); addView(label(subtitle, 12f, Color.rgb(107, 114, 128), false), margins(ViewGroup.LayoutParams.WRAP_CONTENT, top = 2)) }
        row.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)); parent.addView(row, margins(ViewGroup.LayoutParams.WRAP_CONTENT, bottom = 10))
    }

    private fun buildCt01PartyStep(parent: LinearLayout, target: String, name: String, citizenId: String, statement: String, agreed: Boolean, signature: Bitmap?, afterIdentity: ((LinearLayout) -> Unit)? = null) {
        val methods = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        methods.addView(actionButton("Quét QR căn cước", Color.rgb(22, 35, 63)) { collectCt01Form(); pendingCt01ScanTarget = target; startCameraScan() }, LinearLayout.LayoutParams(0, dp(47), 1f).apply { marginEnd = dp(5) })
        methods.addView(actionButton("Chọn ảnh thư viện", Color.WHITE, deepBlue) { collectCt01Form(); pendingCt01ScanTarget = target; pickQrImage.launch("image/*") }, LinearLayout.LayoutParams(0, dp(47), 1f).apply { marginStart = dp(5) })
        parent.addView(methods, margins(ViewGroup.LayoutParams.WRAP_CONTENT, bottom = 12))
        parent.addView(wizardIdentityResult(name, citizenId), margins(ViewGroup.LayoutParams.WRAP_CONTENT, bottom = 12))
        afterIdentity?.invoke(parent)
        parent.addView(agreementCard(target, statement, agreed), margins(ViewGroup.LayoutParams.WRAP_CONTENT, bottom = 12))
        val role = when (target) { "subject" -> "Người kê khai"; "head" -> "Chủ hộ"; "owner" -> "Chủ sở hữu"; else -> "Cha/mẹ/người giám hộ" }
        val signaturePair = signatureCard(role, name, signature) { clearCt01Signature(target) }
        ct01ActiveSignatureTarget = target; ct01ActiveSignatureView = signaturePair.second
        parent.addView(signaturePair.first)
    }

    private fun wizardIdentityResult(name: String, citizenId: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)); background = rounded(Color.WHITE, 13f, Color.rgb(227, 225, 218))
        if (name.isBlank() || citizenId.isBlank()) addView(label("Chưa có dữ liệu căn cước", 13f, Color.rgb(107, 114, 128), true).apply { gravity = Gravity.CENTER })
        else {
            addView(label("✓  Đã đọc dữ liệu thành công", 11f, green, true), margins(ViewGroup.LayoutParams.WRAP_CONTENT, bottom = 8))
            addView(label("Họ và tên", 11f, Color.rgb(107, 114, 128), false)); addView(label(name.uppercase(Locale("vi", "VN")), 14f, navy, true), margins(ViewGroup.LayoutParams.WRAP_CONTENT, top = 2, bottom = 8))
            addView(label("Số định danh", 11f, Color.rgb(107, 114, 128), false)); addView(label(citizenId.chunked(4).joinToString(" "), 15f, navy, true), margins(ViewGroup.LayoutParams.WRAP_CONTENT, top = 2))
        }
    }

    private fun agreementCard(target: String, statement: String, agreed: Boolean): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(13), dp(11), dp(13), dp(11)); background = rounded(if (agreed) Color.rgb(231, 243, 236) else Color.rgb(238, 241, 246), 11f, if (agreed) Color.rgb(165, 207, 182) else Color.rgb(220, 225, 234)); isClickable = true
        val state = label(if (agreed) "✓  Đã đồng ý và cam kết" else "□  Chạm để xác nhận đồng ý", 13f, if (agreed) green else navy, true)
        addView(state); addView(label(statement, 12f, Color.rgb(79, 88, 105), false).apply { setLineSpacing(dp(2).toFloat(), 1f) }, margins(ViewGroup.LayoutParams.WRAP_CONTENT, top = 6))
        setOnClickListener {
            setCt01Agreement(target, !getCt01Agreement(target)); persistActiveSignature(); showCt01Wizard()
        }
    }

    private fun wizardSkip(parent: LinearLayout, text: String) {
        parent.addView(label("✓  $text", 13f, green, true).apply { gravity = Gravity.CENTER; setPadding(dp(16), dp(20), dp(16), dp(20)); background = rounded(Color.rgb(241, 247, 240), 13f, Color.rgb(207, 225, 201)); setLineSpacing(dp(2).toFloat(), 1f) })
    }

    private fun wizardExportRow(parent: LinearLayout, code: String, title: String, subtitle: String, action: () -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(13), dp(12), dp(13), dp(12)); background = rounded(Color.WHITE, 12f, Color.rgb(227, 225, 218)); isClickable = true; setOnClickListener { action() } }
        row.addView(label(code, 11f, Color.WHITE, true).apply { gravity = Gravity.CENTER; background = rounded(Color.rgb(22, 35, 63), 9f) }, LinearLayout.LayoutParams(dp(44), dp(40)).apply { marginEnd = dp(12) })
        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(label(title, 14f, navy, true)); addView(label(subtitle, 11f, Color.rgb(107, 114, 128), false), margins(ViewGroup.LayoutParams.WRAP_CONTENT, top = 2)) }
        row.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)); row.addView(label("›", 24f, Color.rgb(184, 134, 46), true)); parent.addView(row, margins(ViewGroup.LayoutParams.WRAP_CONTENT, bottom = 9))
    }

    private fun ct01SignatureStatusCard(): View = label(buildString {
        append(if (ct01Data.declarantFillsAllSignatureRoles()) "— Người kê khai: không cần ký" else if (ct01Data.applicantAgreed) "✓ Người kê khai" else "○ Người kê khai")
        append("\n")
        append(if (ct01Data.declarantIsHead()) "— Chủ hộ: để trống theo quy định" else if (ct01Data.headAgreed) "✓ Chủ hộ" else "○ Chủ hộ")
        append("\n")
        append(if (ct01Data.declarantIsOwner()) "— Chủ sở hữu: để trống theo quy định" else if (ct01Data.ownerAgreed) "✓ Chủ sở hữu" else "○ Chủ sở hữu")
        if (ct01Data.requiresGuardian) {
            append("\n")
            append(if (ct01Data.declarantIsGuardian()) "— Cha/mẹ/người giám hộ: để trống theo quy định" else if (ct01Data.guardianAgreed) "✓ Cha/mẹ/người giám hộ" else "○ Cha/mẹ/người giám hộ")
        }
    }, 11f, navy, true).apply { gravity = Gravity.CENTER; setPadding(dp(10), dp(10), dp(10), dp(10)); background = rounded(Color.WHITE, 10f, Color.rgb(227, 225, 218)) }

    private fun showCt01ListPicker(title: String, items: Array<String>, selected: (String) -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setItems(items) { _, which -> selected(items[which]) }.setNegativeButton("Hủy", null).show()
    }

    private fun provinceChoices() = arrayOf("Thành phố Hồ Chí Minh", "Thành phố Hà Nội", "Thành phố Hải Phòng", "Thành phố Huế", "Thành phố Đà Nẵng", "Thành phố Cần Thơ", "Tỉnh An Giang", "Tỉnh Bắc Ninh", "Tỉnh Cà Mau", "Tỉnh Cao Bằng", "Tỉnh Đắk Lắk", "Tỉnh Điện Biên", "Tỉnh Đồng Nai", "Tỉnh Đồng Tháp", "Tỉnh Gia Lai", "Tỉnh Hà Tĩnh", "Tỉnh Hưng Yên", "Tỉnh Khánh Hòa", "Tỉnh Lai Châu", "Tỉnh Lâm Đồng", "Tỉnh Lạng Sơn", "Tỉnh Lào Cai", "Tỉnh Nghệ An", "Tỉnh Ninh Bình", "Tỉnh Phú Thọ", "Tỉnh Quảng Ngãi", "Tỉnh Quảng Ninh", "Tỉnh Quảng Trị", "Tỉnh Sơn La", "Tỉnh Tây Ninh", "Tỉnh Thái Nguyên", "Tỉnh Thanh Hóa", "Tỉnh Tuyên Quang", "Tỉnh Vĩnh Long")

    private fun wardSuggestions(province: String): Array<String> = when (province) {
        "Thành phố Hồ Chí Minh" -> arrayOf("phường Bình Quới", "phường Thạnh Mỹ Tây", "phường Bình Thạnh", "phường Sài Gòn", "phường Bến Thành", "phường Chợ Lớn", "phường Tân Định", "phường Gia Định", "phường Thủ Đức", "xã Bình Chánh", "xã Tân Nhựt")
        "Thành phố Hà Nội" -> arrayOf("phường Ba Đình", "phường Hoàn Kiếm", "phường Tây Hồ", "phường Cầu Giấy", "phường Đống Đa", "phường Hai Bà Trưng", "phường Thanh Xuân", "phường Hà Đông")
        "Thành phố Đà Nẵng" -> arrayOf("phường Hải Châu", "phường Thanh Khê", "phường Sơn Trà", "phường Ngũ Hành Sơn", "phường Cẩm Lệ", "phường Liên Chiểu")
        "Thành phố Hải Phòng" -> arrayOf("phường Hồng Bàng", "phường Ngô Quyền", "phường Lê Chân", "phường Hải An", "phường Kiến An")
        "Thành phố Cần Thơ" -> arrayOf("phường Ninh Kiều", "phường Cái Răng", "phường Bình Thủy", "phường Ô Môn", "phường Thốt Nốt")
        "Thành phố Huế" -> arrayOf("phường Thuận Hóa", "phường Phú Xuân", "phường Vỹ Dạ", "phường An Cựu", "phường Hương Long")
        else -> emptyArray()
    }

    private fun showWardPicker(province: String, selected: (String) -> Unit) {
        val manualLabel = "Nhập phường / xã khác…"
        val items = wardSuggestions(province).toMutableList().apply { add(manualLabel) }.toTypedArray()
        showCt01ListPicker("Chọn phường / xã", items) { value ->
            if (value != manualLabel) selected(value) else {
                val input = EditText(this).apply { hint = "Ví dụ: phường Bình Quới"; setPadding(dp(14), dp(8), dp(14), dp(8)) }
                AlertDialog.Builder(this).setTitle("Nhập đúng tên phường / xã").setView(input)
                    .setPositiveButton("Áp dụng") { _, _ -> input.text.toString().trim().takeIf { it.isNotBlank() }?.let(selected) }
                    .setNegativeButton("Hủy", null).show()
            }
        }
    }

    private fun composeAddress(detail: String, ward: String, province: String): String =
        listOf(detail, ward, province).map { it.trim().trim(',') }.filter { it.isNotBlank() }.distinct().joinToString(", ")

    private fun requestAutomaticLocation(target: String) {
        pendingLocationTarget = target
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine || coarse) resolveCurrentLocation()
        else requestLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun resolveCurrentLocation() {
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).filter {
            runCatching { manager.isProviderEnabled(it) }.getOrDefault(false)
        }
        if (providers.isEmpty()) { toast("Hãy bật Vị trí trên điện thoại hoặc chọn địa chỉ thủ công"); return }
        val latest = providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        val freshLocation = latest?.takeIf { System.currentTimeMillis() - it.time <= 120_000L }
        if (freshLocation != null) { resolveLocationAddress(freshLocation); return }
        val hasFineLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val provider = if (hasFineLocation && LocationManager.GPS_PROVIDER in providers) LocationManager.GPS_PROVIDER else providers.first()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            manager.getCurrentLocation(provider, null, ContextCompat.getMainExecutor(this)) { location ->
                if (location != null) resolveLocationAddress(location) else toast("Chưa lấy được vị trí. Vui lòng thử lại hoặc chọn thủ công.")
            }
        } else {
            manager.requestSingleUpdate(provider, object : LocationListener {
                override fun onLocationChanged(location: Location) = resolveLocationAddress(location)
            }, Looper.getMainLooper())
        }
    }

    private fun resolveLocationAddress(location: Location) {
        cameraExecutor.execute {
            val address = runCatching {
                @Suppress("DEPRECATION")
                Geocoder(this, Locale("vi", "VN")).getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull()
            }.getOrNull()
            runOnUiThread {
                if (address == null) toast("Đã lấy tọa độ nhưng chưa nhận được địa chỉ. Vui lòng chọn thủ công.")
                else applyResolvedAddress(address)
            }
        }
    }

    private fun applyResolvedAddress(address: Address) {
        val province = normalizeProvince(address.adminArea ?: address.locality.orEmpty())
        val ward = resolveWard(address, province)
        val street = listOf(address.subThoroughfare, address.thoroughfare).filterNotNull().filter { it.isNotBlank() }.distinct().joinToString(" ")
        when (pendingLocationTarget) {
            "contract" -> {
                if (contract.placeDetail.isBlank()) contract.placeDetail = street
                if (ward.isNotBlank()) contract.ward = ward
                contract.province = province
                contract.place = composeAddress(contract.placeDetail, contract.ward, contract.province)
                showContractWizard()
            }
            "ct01" -> {
                if (ct01Data.newAddressDetail.isBlank()) ct01Data.newAddressDetail = street
                if (ward.isNotBlank()) ct01Data.ward = ward
                ct01Data.province = province
                updateCt01GeneratedText(); showCt01Wizard()
            }
        }
        if (ward.isBlank()) toast("Đã xác định tỉnh/thành. Vui lòng chọn đúng phường/xã để bảo đảm chính xác.")
        else toast("Đã tự động điền đúng cấp phường/xã theo vị trí hiện tại")
    }

    private fun resolveWard(address: Address, province: String): String {
        val fromAddressLine = (0..address.maxAddressLineIndex).flatMap { index ->
            address.getAddressLine(index).orEmpty().split(',')
        }
        val candidates = listOfNotNull(address.subLocality) + fromAddressLine
        return candidates.map { it.trim() }.firstOrNull { value ->
            val lower = value.lowercase(Locale("vi", "VN"))
            value.isNotBlank() && !value.equals(province, true) &&
                (lower.startsWith("phường ") || lower.startsWith("xã ") || lower.startsWith("thị trấn "))
        }.orEmpty()
    }

    private fun normalizeProvince(raw: String): String {
        val known = provinceChoices().firstOrNull { it.equals(raw, true) || it.endsWith(raw, true) }
        return known ?: raw
    }

    private fun persistCt01WizardStep() {
        collectCt01Form(); persistActiveSignature()
        if (ct01Step == 3 || ct01Step == 7) updateCt01GeneratedText()
        if (ct01Step == 4) ct01Data.requiresGuardian = ct01Data.requiresGuardian || isCt01SubjectMinor()
    }

    private fun persistActiveSignature() {
        val bitmap = ct01ActiveSignatureView?.asBitmap() ?: return
        when (ct01ActiveSignatureTarget) { "subject" -> ct01DeclarantSignature = bitmap; "head" -> ct01HeadSignature = bitmap; "owner" -> ct01OwnerSignature = bitmap; "guardian" -> ct01GuardianSignature = bitmap }
    }

    private fun clearCt01Signature(target: String) {
        when (target) { "subject" -> ct01DeclarantSignature = null; "head" -> ct01HeadSignature = null; "owner" -> ct01OwnerSignature = null; "guardian" -> ct01GuardianSignature = null }
    }

    private fun getCt01Agreement(target: String): Boolean = when (target) { "subject" -> ct01Data.applicantAgreed; "head" -> ct01Data.headAgreed; "owner" -> ct01Data.ownerAgreed; else -> ct01Data.guardianAgreed }
    private fun setCt01Agreement(target: String, value: Boolean) { when (target) { "subject" -> ct01Data.applicantAgreed = value; "head" -> ct01Data.headAgreed = value; "owner" -> ct01Data.ownerAgreed = value; "guardian" -> ct01Data.guardianAgreed = value } }

    private fun autoFillCt01HeadFromSubject() {
        ct01Data.headName = ct01Data.declarantName; ct01Data.headCitizenId = ct01Data.citizenId; ct01Data.relationshipToHead = "Chủ hộ"; ct01Data.headAgreed = true
    }

    private fun autoFillCt01OwnerFromHead() {
        ct01Data.legalOwnerName = ct01Data.headName; ct01Data.legalOwnerCitizenId = ct01Data.headCitizenId; ct01Data.ownerAgreed = true
    }

    private fun validateCt01WizardStep(): Boolean {
        fun reject(text: String): Boolean { toast(text); return false }
        return when (ct01Step) {
            1 -> if (ct01Data.authority.isBlank()) reject("Vui lòng chọn cơ quan tiếp nhận") else true
            2 -> if (ct01Data.householdType.isBlank()) reject("Vui lòng chọn loại hình tạm trú") else true
            3 -> if (ct01Data.newAddressDetail.isBlank() || ct01Data.ward.isBlank() || ct01Data.province.isBlank()) reject("Vui lòng nhập đủ số nhà/đường, phường/xã và tỉnh/thành") else true
            4 -> if (ct01Data.declarantName.isBlank() || ct01Data.citizenId.length != 12) reject("Vui lòng quét CCCD người kê khai") else if (ct01Data.phone.isBlank()) reject("Vui lòng nhập số điện thoại") else if (!ct01Data.applicantAgreed) reject("Vui lòng xác nhận cam kết của người kê khai") else true
            5 -> if (ct01Data.householdType != "Lập hộ tạm trú riêng" && (ct01Data.headName.isBlank() || !ct01Data.headAgreed)) reject("Vui lòng quét CCCD và xác nhận ý kiến chủ hộ") else true
            6 -> if (!ct01Data.headIsLegalOwner && (ct01Data.legalOwnerName.isBlank() || !ct01Data.ownerAgreed)) reject("Vui lòng quét CCCD và xác nhận ý kiến chủ sở hữu") else true
            7 -> if (ct01Data.requiresGuardian && (ct01Data.guardianName.isBlank() || !ct01Data.guardianAgreed)) reject("Vui lòng bổ sung người giám hộ và xác nhận đồng ý") else true
            8 -> {
                val invalid = ct01Data.members.firstOrNull { it.name.isBlank() || !it.citizenId.matches(Regex("\\d{12}")) || it.gender !in listOf("Nam", "Nữ") || it.relationship.isBlank() }
                if (invalid != null) reject("Vui lòng kiểm tra lại thông tin của ${invalid.name.ifBlank { "thành viên" }}") else true
            }
            else -> true
        }
    }

    private fun showCt01LegacyForm() {
        ct01Fields.clear()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(24))
        }
        body.addView(label("Tờ khai CT01", 22f, navy, true))
        body.addView(label("Dữ liệu đã quét được điền sẵn. Kiểm tra lại trước khi xuất bản.", 12f, Color.rgb(92, 107, 132), false), margins(ViewGroup.LayoutParams.WRAP_CONTENT, top = 3, bottom = 10))

        sectionTitle(body, "1 · Người được làm thủ tục", "Bạn làm thủ tục này cho ai?")
        ct01SmartField(body, "filingMode", "Người được làm thủ tục", ct01Data.filingMode) {
            val choices = arrayOf("Cho chính tôi", "Làm hộ cho người khác")
            AlertDialog.Builder(this).setTitle("Bạn làm thủ tục này cho ai?").setItems(choices) { _, which ->
                collectCt01Form(); ct01Data.filingMode = choices[which]; showCt01Form()
            }.show()
        }
        if (ct01Data.filingMode == "Làm hộ cho người khác") {
            body.addView(actionButton("Quét CCCD người được làm hộ", blue) {
                collectCt01Form(); pendingCt01ScanTarget = "subject"; startCameraScan()
            }, margins(dp(48), top = 8, bottom = 4))
            body.addView(actionButton("Chọn ảnh QR CCCD", Color.WHITE, blue) {
                collectCt01Form(); pendingCt01ScanTarget = "subject"; pickQrImage.launch("image/*")
            }, margins(dp(46), bottom = 8))
        }

        sectionTitle(body, "2 · Thông tin cá nhân — Mục 1 đến 6", "Quét QR tự điền; chỉ cần bổ sung số điện thoại và email")
        ct01Field(body, "declarantName", "Họ, chữ đệm và tên khai sinh", ct01Data.declarantName)
        ct01DateField(body, "birthDate", "Ngày, tháng, năm sinh", ct01Data.birthDate)
        ct01SmartField(body, "gender", "Giới tính", ct01Data.gender) {
            val choices = arrayOf("Nam", "Nữ")
            AlertDialog.Builder(this).setTitle("Chọn giới tính").setItems(choices) { _, which -> ct01Fields["gender"]?.setText(choices[which]) }.show()
        }
        ct01Field(body, "citizenId", "Số định danh cá nhân", ct01Data.citizenId)
        ct01Field(body, "phone", "Số điện thoại liên hệ", ct01Data.phone)
        ct01Field(body, "email", "Email (không bắt buộc)", ct01Data.email)
        if (ct01Data.oldPermanentAddress.isNotBlank()) {
            body.addView(label("Thường trú cũ từ QR: ${ct01Data.oldPermanentAddress}", 11f, Color.rgb(91, 106, 131), false).apply {
                setPadding(dp(12), dp(10), dp(12), dp(10)); background = rounded(Color.rgb(244, 248, 253), 9f, border)
            }, margins(ViewGroup.LayoutParams.WRAP_CONTENT, top = 8))
        }

        sectionTitle(body, "3 · Nơi chuyển đến", "Địa chỉ này dùng để tạo Kính gửi và nội dung đề nghị")
        ct01SmartField(body, "procedureType", "Thủ tục cần thực hiện", ct01Data.procedureType) {
            val choices = arrayOf("Đăng ký tạm trú", "Đăng ký thường trú", "Gia hạn tạm trú", "Tách hộ", "Điều chỉnh thông tin cư trú")
            AlertDialog.Builder(this).setTitle("Chọn thủ tục").setItems(choices) { _, which ->
                collectCt01Form(); ct01Data.procedureType = choices[which]; updateCt01GeneratedText(); showCt01Form()
            }.show()
        }
        ct01SmartField(body, "province", "Tỉnh / Thành phố", ct01Data.province) {
            val choices = arrayOf("Thành phố Hà Nội", "Thành phố Hồ Chí Minh", "Thành phố Hải Phòng", "Thành phố Huế", "Thành phố Đà Nẵng", "Thành phố Cần Thơ", "Tỉnh An Giang", "Tỉnh Bắc Ninh", "Tỉnh Cà Mau", "Tỉnh Cao Bằng", "Tỉnh Đắk Lắk", "Tỉnh Điện Biên", "Tỉnh Đồng Nai", "Tỉnh Đồng Tháp", "Tỉnh Gia Lai", "Tỉnh Hà Tĩnh", "Tỉnh Hưng Yên", "Tỉnh Khánh Hòa", "Tỉnh Lai Châu", "Tỉnh Lâm Đồng", "Tỉnh Lạng Sơn", "Tỉnh Lào Cai", "Tỉnh Nghệ An", "Tỉnh Ninh Bình", "Tỉnh Phú Thọ", "Tỉnh Quảng Ngãi", "Tỉnh Quảng Ninh", "Tỉnh Quảng Trị", "Tỉnh Sơn La", "Tỉnh Tây Ninh", "Tỉnh Thái Nguyên", "Tỉnh Thanh Hóa", "Tỉnh Tuyên Quang", "Tỉnh Vĩnh Long")
            AlertDialog.Builder(this).setTitle("Chọn tỉnh / thành phố").setItems(choices) { _, which ->
                collectCt01Form(); ct01Data.province = choices[which]; updateCt01GeneratedText(); showCt01Form()
            }.show()
        }
        ct01Field(body, "district", "Quận / Huyện / Thành phố (nếu địa chỉ có)", ct01Data.district)
        ct01Field(body, "ward", "Phường / Xã", ct01Data.ward)
        ct01Field(body, "newAddressDetail", "Số nhà, đường, thôn, ấp", ct01Data.newAddressDetail, true)
        body.addView(actionButton("Tự tạo cơ quan tiếp nhận và địa chỉ", Color.WHITE, blue) {
            collectCt01Form(); updateCt01GeneratedText(); showCt01Form()
        }, margins(dp(46), top = 8, bottom = 8))
        ct01Field(body, "authority", "Kính gửi", ct01Data.authority, true)

        sectionTitle(body, "4 · Chủ hộ — Mục 7 đến 9", "Chủ hộ là gì của bạn?")
        ct01SmartField(body, "housingSituation", "Nhà bạn tự sở hữu hay thuê / ở nhờ?", ct01Data.housingSituation) {
            val choices = arrayOf("Tôi là chủ nhà / đứng tên hộ mới", "Thuê nhà / ở nhờ", "Ở cùng người thân")
            AlertDialog.Builder(this).setTitle("Mối quan hệ với chỗ ở mới").setItems(choices) { _, which ->
                collectCt01Form(); ct01Data.housingSituation = choices[which]
                if (which == 0) {
                    ct01Data.headName = ct01Data.declarantName; ct01Data.headCitizenId = ct01Data.citizenId
                    ct01Data.relationshipToHead = "Chủ hộ"; ct01Data.headIsLegalOwner = true
                    ct01Data.legalOwnerName = ct01Data.declarantName; ct01Data.legalOwnerCitizenId = ct01Data.citizenId
                }
                updateCt01GeneratedText(); showCt01Form()
            }.show()
        }
        if (ct01Data.housingSituation == "Thuê nhà / ở nhờ") {
            body.addView(label("Lưu ý hồ sơ: chuẩn bị giấy tờ về việc thuê/ở nhờ và ý kiến đồng ý của chủ hộ, chủ sở hữu theo trường hợp thực tế.", 11f, Color.rgb(126, 75, 12), true).apply {
                setPadding(dp(12), dp(10), dp(12), dp(10)); background = rounded(Color.rgb(255, 247, 226), 9f, Color.rgb(234, 196, 112))
            }, margins(ViewGroup.LayoutParams.WRAP_CONTENT, top = 8, bottom = 4))
        }
        ct01Field(body, "headName", "Họ, chữ đệm và tên chủ hộ gia đình mới", ct01Data.headName)
        ct01SmartField(body, "relationshipToHead", "Mối quan hệ với chủ hộ", ct01Data.relationshipToHead) {
            val choices = arrayOf("Chủ hộ", "Vợ", "Chồng", "Con", "Cha", "Mẹ", "Cháu", "Ở nhờ", "Ở mượn", "Ở thuê", "Cùng ở nhờ", "Cùng ở mượn", "Cùng ở thuê", "Khác")
            AlertDialog.Builder(this).setTitle("Chọn quan hệ với chủ hộ").setItems(choices) { _, which -> ct01Fields["relationshipToHead"]?.setText(choices[which]) }.show()
        }
        ct01Field(body, "headCitizenId", "Số định danh cá nhân của chủ hộ", ct01Data.headCitizenId)
        ct01SmartField(body, "headIsLegalOwner", "Chủ hộ đồng thời là chủ sở hữu nhà?", if (ct01Data.headIsLegalOwner) "Có" else "Không") {
            val choices = arrayOf("Có", "Không")
            AlertDialog.Builder(this).setTitle("Chủ hộ có đồng thời là chủ sở hữu?").setItems(choices) { _, which ->
                collectCt01Form(); ct01Data.headIsLegalOwner = which == 0
                if (ct01Data.headIsLegalOwner) {
                    ct01Data.legalOwnerName = ct01Data.headName; ct01Data.legalOwnerCitizenId = ct01Data.headCitizenId
                }
                updateCt01GeneratedText(); showCt01Form()
            }.show()
        }
        ct01Field(body, "requestContent", "Nội dung đề nghị", ct01Data.requestContent, true)

        sectionTitle(body, "5 · Thành viên cùng thay đổi — Mục 11", "Bỏ qua nếu làm một mình; tối đa 09 người đúng số dòng của mẫu")
        val summary = if (ct01Data.members.isEmpty()) "Chưa chọn thành viên" else ct01Data.members.joinToString("\n") { "• ${it.name} · ${it.citizenId.takeLast(4)}" }
        body.addView(label(summary, 12f, if (ct01Data.members.isEmpty()) Color.rgb(118, 130, 150) else navy, false).apply {
            setPadding(dp(13), dp(11), dp(13), dp(11)); background = rounded(Color.rgb(245, 248, 253), 10f, border)
        }, margins(ViewGroup.LayoutParams.WRAP_CONTENT, bottom = 8))
        body.addView(actionButton("Quét QR để thêm thành viên", Color.WHITE, blue) {
            collectCt01Form(); pendingCt01ScanTarget = "member"; startCameraScan()
        }, margins(dp(46), bottom = 8))
        body.addView(actionButton("+ Thêm thành viên thủ công", Color.WHITE, blue) {
            collectCt01Form(); showAddCt01MemberDialog()
        }, margins(dp(46), bottom = 8))
        if (ct01Data.members.isNotEmpty()) {
            body.addView(actionButton("Kiểm tra giới tính và quan hệ thành viên", Color.WHITE, blue) {
                collectCt01Form(); showCt01MemberEditor()
            }, margins(dp(46), bottom = 8))
        }

        sectionTitle(body, "6 · Xác nhận và ký", signatureRequirementText())
        ct01SmartField(body, "consentMethod", "Phương thức xác nhận đồng ý", ct01Data.consentMethod) {
            val choices = arrayOf("Ký trực tiếp trên tờ khai", "Xác nhận qua VNeID", "Văn bản đồng ý riêng")
            AlertDialog.Builder(this).setTitle("Chọn phương thức xác nhận").setItems(choices) { _, which -> ct01Fields["consentMethod"]?.setText(choices[which]) }.show()
        }
        ct01Field(body, "headConsent", "Ý kiến của chủ hộ", ct01Data.headConsent, true)
        if (!ct01Data.headIsLegalOwner) {
            ct01Field(body, "legalOwnerName", "Họ và tên chủ sở hữu", ct01Data.legalOwnerName)
            ct01Field(body, "legalOwnerCitizenId", "Số định danh cá nhân chủ sở hữu", ct01Data.legalOwnerCitizenId)
            ct01Field(body, "ownerConsent", "Ý kiến của chủ sở hữu chỗ ở hợp pháp", ct01Data.ownerConsent, true)
            body.addView(actionButton("Tạo và sao chép câu đồng ý chuẩn", Color.WHITE, green) {
                collectCt01Form(); val consent = buildOwnerConsent(); ct01Data.ownerConsent = consent
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Ý kiến đồng ý CT01", consent))
                toast("Đã sao chép câu đồng ý"); showCt01Form()
            }, margins(dp(46), top = 8, bottom = 8))
        }
        ct01Field(body, "guardianName", "Họ tên cha, mẹ hoặc người giám hộ (nếu áp dụng)", ct01Data.guardianName)
        ct01Field(body, "guardianCitizenId", "Số định danh cha, mẹ hoặc người giám hộ", ct01Data.guardianCitizenId)
        ct01Field(body, "guardianConsent", "Ý kiến của cha, mẹ hoặc người giám hộ", ct01Data.guardianConsent, true)
        ct01Field(body, "signingPlace", "Địa điểm ký", ct01Data.signingPlace)
        ct01DateField(body, "signingDate", "Ngày ký", ct01Data.signingDate)
        showCt01Shell("Nhập thông tin CT01", ScrollView(this).apply { addView(body) }, "Nhập")
    }

    private fun ct01Field(parent: LinearLayout, key: String, caption: String, value: String, multiLine: Boolean = false) {
        parent.addView(label(caption, 12f, navy, true), margins(ViewGroup.LayoutParams.WRAP_CONTENT, top = 8, bottom = 4))
        val input = EditText(this).apply {
            setText(value); hint = ct01ExampleHint(key); textSize = 15f; setTextColor(Color.rgb(21, 28, 41)); setHintTextColor(Color.rgb(145, 151, 163)); setPadding(dp(13), dp(8), dp(13), dp(8))
            background = rounded(Color.WHITE, 8f, border); minHeight = dp(if (multiLine) 58 else 46)
            maxLines = if (multiLine) 4 else 1; isSingleLine = !multiLine
            when (key) {
                "citizenId", "headCitizenId", "legalOwnerCitizenId", "guardianCitizenId" -> {
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    filters = arrayOf(android.text.InputFilter.LengthFilter(12))
                }
                "phone" -> inputType = android.text.InputType.TYPE_CLASS_PHONE
                "email" -> inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            }
        }
        ct01Fields[key] = input
        parent.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun ct01SmartField(parent: LinearLayout, key: String, caption: String, value: String, chooser: () -> Unit) {
        parent.addView(label(caption, 12f, navy, true), margins(ViewGroup.LayoutParams.WRAP_CONTENT, top = 8, bottom = 4))
        val input = EditText(this).apply {
            setText(value); hint = ct01ExampleHint(key); textSize = 15f; setTextColor(Color.rgb(21, 28, 41)); setHintTextColor(Color.rgb(145, 151, 163)); setPadding(dp(13), dp(8), dp(10), dp(8))
            background = rounded(Color.WHITE, 8f, Color.rgb(164, 190, 232)); minHeight = dp(48)
            isFocusable = false; isCursorVisible = false; isClickable = true
            setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_expand_more, 0)
            setOnClickListener { chooser() }
        }
        ct01Fields[key] = input
        parent.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun ct01ExampleHint(key: String): String = when (key) {
        "authority" -> "Ví dụ: Công an phường Bình Quới"
        "newAddressDetail" -> "Ví dụ: 12 Lê Lợi, Khu phố 2"
        "ward" -> "Chọn phường / xã"
        "province" -> "Chọn tỉnh / thành phố"
        "temporaryUntil" -> "Ví dụ: 30/09/2027"
        "phone" -> "Ví dụ: 0982345678"
        "email" -> "Ví dụ: nguyenvanan@email.com"
        "headName", "legalOwnerName", "guardianName" -> "Ví dụ: Nguyễn Văn An"
        "headCitizenId", "legalOwnerCitizenId", "guardianCitizenId", "citizenId" -> "Ví dụ: 079096001234"
        else -> "Chạm để nhập hoặc chọn"
    }

    private fun ct01DateField(parent: LinearLayout, key: String, caption: String, value: String) {
        ct01SmartField(parent, key, caption, value) {
            val target = ct01Fields[key] ?: return@ct01SmartField
            val parts = target.text.toString().split('/').mapNotNull { it.toIntOrNull() }
            val now = Calendar.getInstance()
            val day = parts.getOrNull(0)?.coerceIn(1, 31) ?: now.get(Calendar.DAY_OF_MONTH)
            val month = parts.getOrNull(1)?.coerceIn(1, 12)?.minus(1) ?: now.get(Calendar.MONTH)
            val year = parts.getOrNull(2)?.coerceIn(1900, 2200) ?: now.get(Calendar.YEAR)
            DatePickerDialog(this, { _, selectedYear, selectedMonth, selectedDay ->
                target.setText(String.format(Locale.US, "%02d/%02d/%04d", selectedDay, selectedMonth + 1, selectedYear))
            }, year, month, day).show()
        }
    }

    private fun applyCitizenToCt01Subject(citizen: ScannedCitizen) {
        ct01Data.declarantName = vietnameseTitleCase(citizen.name)
        ct01Data.birthDate = citizen.birthDate
        ct01Data.gender = citizen.gender
        ct01Data.citizenId = citizen.citizenId
        ct01Data.oldPermanentAddress = citizen.permanentAddress
        ct01Fields.clear()
    }

    private fun defaultMemberRelationship(): String = when (ct01Data.housingSituation) {
        "Thuê nhà / ở nhờ" -> "Cùng ở thuê"
        "Ở cùng người thân" -> "Con"
        else -> "Con"
    }

    private fun updateCt01GeneratedText() {
        val addressParts = listOf(ct01Data.newAddressDetail, ct01Data.ward, ct01Data.district, ct01Data.province)
            .map { it.trim().trim(',') }.filter { it.isNotBlank() }.distinct()
        ct01Data.newAddress = addressParts.joinToString(", ")
        val ward = ct01Data.ward.trim()
        ct01Data.authority = if (ward.isNotBlank()) "Công an $ward, ${ct01Data.province}" else "Cơ quan đăng ký cư trú ${ct01Data.province}"
        val until = ct01Data.temporaryUntil.takeIf { it.isNotBlank() }?.let { ", đến ngày $it" }.orEmpty()
        ct01Data.requestContent = "${ct01Data.procedureType} tại: ${ct01Data.newAddress}$until"
        if (ct01Data.signingPlace.isBlank()) ct01Data.signingPlace = ct01Data.province
        ct01Data.headConsent = "Đồng ý cho ${ct01Data.declarantName} ${ct01Data.procedureType.lowercase(Locale("vi", "VN"))} tại địa chỉ nêu trên"
        ct01Data.ownerConsent = buildOwnerConsent()
        ct01Data.guardianConsent = if (ct01Data.requiresGuardian)
            "Tôi đồng ý cho ${ct01Data.declarantName.ifBlank { "người được giám hộ" }} ${ct01Data.procedureType.lowercase(Locale("vi", "VN"))} theo nội dung đã khai."
        else ""
    }

    private fun buildOwnerConsent(): String {
        val owner = ct01Data.legalOwnerName.ifBlank { "[Họ tên chủ sở hữu]" }
        val subject = ct01Data.declarantName.ifBlank { "[Họ tên người đăng ký]" }
        val address = ct01Data.newAddress.ifBlank { "[địa chỉ chỗ ở]" }
        return "Tôi là $owner, chủ sở hữu căn nhà tại $address, đồng ý cho ông/bà $subject ${ct01Data.procedureType.lowercase(Locale("vi", "VN"))} tại địa chỉ trên"
    }

    private fun isCt01SubjectMinor(): Boolean {
        val birth = runCatching { java.text.SimpleDateFormat("dd/MM/yyyy", Locale.US).apply { isLenient = false }.parse(ct01Data.birthDate) }.getOrNull() ?: return false
        val birthday = Calendar.getInstance().apply { time = birth }
        val today = Calendar.getInstance()
        var age = today.get(Calendar.YEAR) - birthday.get(Calendar.YEAR)
        if (today.get(Calendar.DAY_OF_YEAR) < birthday.get(Calendar.DAY_OF_YEAR)) age--
        return age < 18
    }

    private fun signatureRequirementText(): String = if (isCt01SubjectMinor())
        "Người dưới 18 tuổi: bắt buộc bổ sung thông tin và chữ ký cha, mẹ hoặc người giám hộ"
    else "App đánh dấu đúng các vị trí cần ký theo trường hợp đã chọn"

    private fun showAddCt01MemberDialog() {
        if (ct01Data.members.size >= 9) { toast("Mẫu CT01 chỉ có tối đa 09 dòng thành viên"); return }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(4), dp(20), 0) }
        fun input(hint: String, numeric: Boolean = false) = EditText(this).apply {
            this.hint = hint; setPadding(dp(10), dp(8), dp(10), dp(8))
            if (numeric) inputType = android.text.InputType.TYPE_CLASS_NUMBER
            content.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
        }
        val name = input("Họ và tên")
        val birth = input("Ngày sinh DD/MM/YYYY")
        val citizenId = input("Số định danh 12 số", true).apply { filters = arrayOf(android.text.InputFilter.LengthFilter(12)) }
        AlertDialog.Builder(this).setTitle("Thêm thành viên").setView(content)
            .setPositiveButton("Tiếp tục") { _, _ ->
                val member = Ct01Member(name.text.toString().trim(), birth.text.toString().trim(), "Nam", citizenId.text.toString().trim(), defaultMemberRelationship(), "")
                ct01Data.members.add(member); showCt01MemberRelationship(ct01Data.members.lastIndex)
            }.setNegativeButton("Hủy", null).show()
    }

    private fun showCt01MemberRelationship(index: Int) {
        val member = ct01Data.members.getOrNull(index) ?: return
        val toDeclarant = arrayOf("Vợ", "Chồng", "Con", "Cha", "Mẹ", "Anh", "Chị", "Em", "Cháu", "Người khác")
        AlertDialog.Builder(this).setTitle("${member.name} có quan hệ gì với người kê khai?")
            .setItems(toDeclarant) { _, first ->
                member.relationshipToDeclarant = toDeclarant[first]
                val toHead = arrayOf("Chủ hộ", "Vợ", "Chồng", "Con", "Cha", "Mẹ", "Cháu", "Ở nhờ", "Ở mượn", "Ở thuê", "Cùng ở thuê", "Khác")
                AlertDialog.Builder(this).setTitle("${member.name} có quan hệ gì với chủ hộ?")
                    .setItems(toHead) { _, second -> member.relationship = toHead[second]; showCt01Form() }
                    .setOnCancelListener { showCt01Form() }.show()
            }.setOnCancelListener { showCt01Form() }.show()
    }

    private fun collectCt01Form() {
        fun value(key: String, old: String) = ct01Fields[key]?.text?.toString()?.trim()?.ifBlank { old } ?: old
        ct01Data.authority = value("authority", ct01Data.authority)
        ct01Data.declarantName = value("declarantName", ct01Data.declarantName)
        ct01Data.birthDate = value("birthDate", ct01Data.birthDate)
        ct01Data.gender = value("gender", ct01Data.gender)
        ct01Data.citizenId = value("citizenId", ct01Data.citizenId)
        ct01Data.phone = value("phone", ct01Data.phone)
        ct01Data.email = value("email", ct01Data.email)
        ct01Data.province = value("province", ct01Data.province)
        ct01Data.district = value("district", ct01Data.district)
        ct01Data.ward = value("ward", ct01Data.ward)
        ct01Data.newAddressDetail = value("newAddressDetail", ct01Data.newAddressDetail)
        ct01Data.temporaryUntil = value("temporaryUntil", ct01Data.temporaryUntil)
        ct01Data.headName = value("headName", ct01Data.headName)
        ct01Data.relationshipToHead = value("relationshipToHead", ct01Data.relationshipToHead)
        ct01Data.headCitizenId = value("headCitizenId", ct01Data.headCitizenId)
        ct01Data.requestContent = value("requestContent", ct01Data.requestContent)
        ct01Data.consentMethod = value("consentMethod", ct01Data.consentMethod)
        ct01Data.headConsent = value("headConsent", ct01Data.headConsent)
        ct01Data.legalOwnerName = value("legalOwnerName", ct01Data.legalOwnerName)
        ct01Data.legalOwnerCitizenId = value("legalOwnerCitizenId", ct01Data.legalOwnerCitizenId)
        ct01Data.ownerConsent = value("ownerConsent", ct01Data.ownerConsent)
        ct01Data.guardianName = value("guardianName", ct01Data.guardianName)
        ct01Data.guardianCitizenId = value("guardianCitizenId", ct01Data.guardianCitizenId)
        ct01Data.guardianConsent = value("guardianConsent", ct01Data.guardianConsent)
        ct01Data.signingPlace = value("signingPlace", ct01Data.signingPlace)
        ct01Data.signingDate = value("signingDate", ct01Data.signingDate)
    }

    private fun showCt01MemberPicker() {
        val tenants = tenantStore.load()
        if (tenants.isEmpty()) { toast("Thư viện người thuê chưa có hồ sơ"); return }
        val labels = tenants.map { "${it.name} · ${it.citizenId.takeLast(4)}" }.toTypedArray()
        val selected = BooleanArray(tenants.size) { index -> ct01Data.members.any { it.citizenId == tenants[index].citizenId } }
        AlertDialog.Builder(this)
            .setTitle("Chọn thành viên cùng thay đổi")
            .setMultiChoiceItems(labels, selected) { _, which, checked -> selected[which] = checked }
            .setPositiveButton("Áp dụng") { _, _ ->
                val chosen = tenants.filterIndexed { index, _ -> selected[index] }
                ct01Data.members = chosen.take(9).map { Ct01Member(it.name, it.birthDate, it.gender, it.citizenId, defaultMemberRelationship(), "") }.toMutableList()
                if (chosen.size > 9) toast("CT01 chỉ hiển thị tối đa 09 thành viên trên một tờ khai")
                showCt01Form()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun showCt01MemberEditor() {
        val labels = ct01Data.members.map { "${it.name} · ${it.gender.ifBlank { "Chưa chọn giới tính" }} · với chủ hộ: ${it.relationship}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Chọn thành viên cần chỉnh").setItems(labels) { _, index ->
            val member = ct01Data.members[index]
            val actions = arrayOf("Chọn giới tính", "Chọn quan hệ với người khai", "Chọn quan hệ với chủ hộ", "Xóa khỏi danh sách")
            AlertDialog.Builder(this).setTitle(member.name).setItems(actions) { _, action ->
                when (action) {
                    0 -> AlertDialog.Builder(this).setTitle("Giới tính").setItems(arrayOf("Nam", "Nữ")) { _, choice ->
                        member.gender = if (choice == 0) "Nam" else "Nữ"; showCt01Form()
                    }.show()
                    1 -> {
                        val relations = arrayOf("Vợ", "Chồng", "Con", "Cha", "Mẹ", "Anh", "Chị", "Em", "Cháu", "Người khác")
                        AlertDialog.Builder(this).setTitle("Quan hệ với người khai").setItems(relations) { _, choice ->
                            member.relationshipToDeclarant = relations[choice]; showCt01Form()
                        }.show()
                    }
                    2 -> {
                        val relations = arrayOf("Vợ", "Chồng", "Con", "Cha", "Mẹ", "Cháu", "Ở nhờ", "Ở mượn", "Ở thuê", "Cùng ở nhờ", "Cùng ở mượn", "Cùng ở thuê", "Khác")
                        AlertDialog.Builder(this).setTitle("Quan hệ với chủ hộ").setItems(relations) { _, choice ->
                            member.relationship = relations[choice]; showCt01Form()
                        }.show()
                    }
                    3 -> { ct01Data.members.removeAt(index); showCt01Form() }
                }
            }.show()
        }.setNegativeButton("Đóng", null).show()
    }

    private fun showCt01Preview() {
        collectCt01Form()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(9), dp(9), dp(9), dp(8)); setBackgroundColor(Color.rgb(218, 226, 239)) }
        box.addView(label("02 trang A4 · Chụm hai ngón để phóng to, kéo để di chuyển", 11f, deepBlue, true).apply {
            gravity = Gravity.CENTER; background = rounded(Color.WHITE, 11f, border); setPadding(dp(8), dp(8), dp(8), dp(8))
        }, margins(dp(40), bottom = 8))
        val warning = buildString {
            append("Cần ký: Người kê khai · Chủ hộ")
            if (!ct01Data.headIsLegalOwner) append(" · Chủ sở hữu nhà")
            if (isCt01SubjectMinor()) append(" · Cha/mẹ/người giám hộ")
        }
        box.addView(label("✍  $warning", 11f, Color.rgb(126, 75, 12), true).apply {
            gravity = Gravity.CENTER; setPadding(dp(8), dp(8), dp(8), dp(8)); background = rounded(Color.rgb(255, 247, 226), 10f, Color.rgb(234, 196, 112))
        }, margins(dp(38), bottom = 8))
        box.addView(ZoomableImageView(this).apply {
            setImageBitmap(Ct01Renderer.renderBitmap(this@MainActivity, ct01Data, ct01DeclarantSignature, ct01OwnerSignature, ct01HeadSignature, ct01GuardianSignature, 2))
            background = rounded(Color.rgb(234, 239, 247), 5f)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        showCt01Shell("Xem trước CT01", box, "Xem")
    }

    private fun showCt01Signatures() {
        collectCt01Form()
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(13), dp(15), dp(13), dp(18)) }
        body.addView(label("Ký xác nhận CT01", 21f, navy, true))
        body.addView(label("Ký đúng khu vực áp dụng. Có thể để trống mục cha, mẹ hoặc người giám hộ nếu không thuộc trường hợp này.", 12f, Color.rgb(92, 106, 130), false), margins(ViewGroup.LayoutParams.WRAP_CONTENT, top = 4, bottom = 12))
        val head = signatureCard("Chủ hộ", ct01Data.headName, ct01HeadSignature) { ct01HeadSignature = null }
        val owner = signatureCard("Chủ sở hữu chỗ ở hợp pháp", ct01Data.legalOwnerName, ct01OwnerSignature) { ct01OwnerSignature = null }
        val guardian = if (isCt01SubjectMinor()) signatureCard("Cha, mẹ hoặc người giám hộ — bắt buộc", ct01Data.guardianName, ct01GuardianSignature) { ct01GuardianSignature = null } else null
        val declarant = signatureCard("Người kê khai", ct01Data.declarantName, ct01DeclarantSignature) { ct01DeclarantSignature = null }
        body.addView(head.first, margins(ViewGroup.LayoutParams.WRAP_CONTENT, bottom = 12))
        body.addView(owner.first, margins(ViewGroup.LayoutParams.WRAP_CONTENT, bottom = 12))
        guardian?.let { body.addView(it.first, margins(ViewGroup.LayoutParams.WRAP_CONTENT, bottom = 12)) }
        body.addView(declarant.first, margins(ViewGroup.LayoutParams.WRAP_CONTENT, bottom = 12))
        body.addView(actionButton("Áp dụng chữ ký vào CT01", blue) {
            ct01HeadSignature = head.second.asBitmap(); ct01OwnerSignature = owner.second.asBitmap()
            ct01GuardianSignature = guardian?.second?.asBitmap(); ct01DeclarantSignature = declarant.second.asBitmap()
            showCt01Preview()
        }, margins(dp(48), bottom = 12))
        showCt01Shell("Ký tên CT01", ScrollView(this).apply { addView(body) }, "Ký")
    }

    private fun showCt01Shell(title: String, content: View, active: String) {
        identityResultVisible = false; templateSelectionVisible = false; ct01Visible = true
        topBar.visibility = View.GONE; bottomNavigation.visibility = View.GONE
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(242, 246, 252)) }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(8), dp(12), dp(8))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(deepBlue, blue))
        }
        header.addView(iconButton("‹", "Quay lại thư viện biểu mẫu") { lastScannedCitizen?.let { showTemplateSelection(it) } }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(9) })
        header.addView(label(title, 18f, Color.WHITE, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(label("CT01", 11f, Color.WHITE, true).apply { gravity = Gravity.CENTER; background = rounded(Color.rgb(18, 112, 185), 9f) }, LinearLayout.LayoutParams(dp(48), dp(32)))
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)))
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(buildCt01Navigation(active), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(61)))
        swapContent(root)
    }

    private fun buildCt01Navigation(active: String): View {
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; setPadding(dp(6), dp(6), dp(6), dp(7)); background = rounded(Color.WHITE, 15f, border) }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER }
        val actions = listOf<Pair<String, () -> Unit>>(
            "Nhập" to { collectCt01Form(); showCt01Form() },
            "Xem" to { showCt01Preview() },
            "Ký" to { showCt01Signatures() },
            "PDF" to { createCt01Pdf() },
            "Word" to { createCt01Docx() },
            "Nộp online" to { showCt01OnlineGuide() },
            "Lưu hình" to { saveCt01Image() }
        )
        actions.forEach { item ->
            val selected = item.first == active
            val buttonWidth = when (item.first) { "Lưu hình" -> 88; "Nộp online" -> 104; else -> 68 }
            row.addView(actionButton(item.first, if (selected) blue else Color.rgb(235, 241, 250), if (selected) Color.WHITE else navy, item.second), LinearLayout.LayoutParams(dp(buttonWidth), dp(47)).apply { marginEnd = dp(5) })
        }
        scroll.addView(row)
        return scroll
    }

    private fun createCt01Pdf() {
        collectCt01Form()
        if (!validateCt01()) return
        runCatching {
            val file = Ct01Renderer.createPdf(this, ct01Data, ct01DeclarantSignature, ct01OwnerSignature, ct01HeadSignature, ct01GuardianSignature)
            publishToDownloads(file, "application/pdf")
            startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(fileUri(file), "application/pdf"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) })
            toast("Đã tạo CT01 gồm 02 trang A4")
        }.onFailure {
            if (it is ActivityNotFoundException) toast("Đã tạo CT01 nhưng thiết bị chưa có ứng dụng đọc PDF") else toast("Không thể tạo CT01: ${it.message}")
        }
    }

    private fun shareCt01Pdf() {
        collectCt01Form()
        if (!validateCt01()) return
        runCatching {
            val file = Ct01Renderer.createPdf(this, ct01Data, ct01DeclarantSignature, ct01OwnerSignature, ct01HeadSignature, ct01GuardianSignature)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_SUBJECT, "Tờ khai CT01 - ${ct01Data.declarantName}")
                putExtra(Intent.EXTRA_STREAM, fileUri(file))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Chia sẻ tờ khai CT01"))
        }.onFailure { toast("Không thể chia sẻ CT01: ${it.message ?: "lỗi không xác định"}") }
    }

    private fun createCt01Docx() {
        collectCt01Form()
        if (!validateCt01()) return
        runCatching {
            val file = Ct01DocxExporter.create(this, ct01Data)
            publishToDownloads(file, "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(fileUri(file), "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }.onSuccess { toast("Đã tạo file Word CT01 có thể chỉnh sửa") }
            .onFailure {
                if (it is ActivityNotFoundException) toast("Đã lưu Word nhưng thiết bị chưa có ứng dụng mở DOCX")
                else toast("Không thể tạo Word CT01: ${it.message}")
            }
    }

    private fun showCt01OnlineGuide() {
        val steps = """1. Xuất PDF và kiểm tra đủ thông tin, chữ ký cần thiết.

2. Chuẩn bị CCCD và giấy tờ chứng minh chỗ ở hợp pháp hoặc hợp đồng thuê nhà theo trường hợp thực tế.

3. Mở Cổng Dịch vụ công Bộ Công an hoặc VNeID, chọn thủ tục cư trú tương ứng.

4. Khai đúng dữ liệu, tải PDF và giấy tờ kèm theo, sau đó kiểm tra trạng thái tiếp nhận."""
        AlertDialog.Builder(this).setTitle("Hướng dẫn nộp online").setMessage(steps)
            .setPositiveButton("Mở Cổng DVC Bộ Công an") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://dichvucong.bocongan.gov.vn/")))
            }.setNeutralButton("Mở VNeID") { _, _ ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://vneid.gov.vn/"))) }
            }.setNegativeButton("Đóng", null).show()
    }

    private fun validateCt01(): Boolean {
        fun reject(message: String): Boolean { toast(message); return false }
        val datePattern = Regex("\\d{2}/\\d{2}/\\d{4}")
        fun validDate(value: String): Boolean = datePattern.matches(value) && runCatching {
            java.text.SimpleDateFormat("dd/MM/yyyy", Locale.US).apply { isLenient = false }.parse(value)
        }.isSuccess
        if (ct01Data.authority.isBlank()) return reject("Vui lòng nhập cơ quan đăng ký cư trú tại mục Kính gửi")
        if (ct01Data.declarantName.isBlank()) return reject("Vui lòng nhập họ tên người có thay đổi thông tin cư trú")
        if (!validDate(ct01Data.birthDate)) return reject("Ngày sinh phải là ngày hợp lệ theo định dạng DD/MM/YYYY")
        if (ct01Data.gender !in listOf("Nam", "Nữ")) return reject("Giới tính chỉ chọn Nam hoặc Nữ")
        if (!ct01Data.citizenId.matches(Regex("\\d{12}"))) return reject("Số định danh người kê khai phải gồm đúng 12 chữ số")
        if (ct01Data.phone.isBlank()) return reject("Vui lòng nhập số điện thoại liên hệ")
        if (ct01Data.email.isNotBlank() && !android.util.Patterns.EMAIL_ADDRESS.matcher(ct01Data.email).matches()) return reject("Địa chỉ email chưa đúng định dạng")
        if (ct01Data.headName.isBlank()) return reject("Vui lòng nhập họ tên chủ hộ gia đình mới")
        if (!ct01Data.headCitizenId.matches(Regex("\\d{12}"))) return reject("Số định danh chủ hộ phải gồm đúng 12 chữ số")
        if (ct01Data.requestContent.isBlank()) return reject("Vui lòng ghi rõ nội dung đề nghị")
        val invalidMember = ct01Data.members.firstOrNull {
            !it.citizenId.matches(Regex("\\d{12}")) || !validDate(it.birthDate) || it.gender !in listOf("Nam", "Nữ") || it.relationship.isBlank()
        }
        if (invalidMember != null) return reject("Hãy kiểm tra ngày sinh, giới tính, số định danh và quan hệ của ${invalidMember.name}")
        if (ct01Data.consentMethod == "Xác nhận qua VNeID") {
            if (ct01Data.legalOwnerName.isBlank()) return reject("Xác nhận qua VNeID cần họ tên chủ sở hữu chỗ ở hợp pháp")
            if (!ct01Data.legalOwnerCitizenId.matches(Regex("\\d{12}"))) return reject("Xác nhận qua VNeID cần số định danh chủ sở hữu gồm đúng 12 chữ số")
            if (ct01Data.guardianName.isNotBlank() && !ct01Data.guardianCitizenId.matches(Regex("\\d{12}"))) {
                return reject("Số định danh của cha, mẹ hoặc người giám hộ phải gồm đúng 12 chữ số")
            }
        }
        if (!ct01Data.headIsLegalOwner && ct01Data.legalOwnerName.isBlank()) return reject("Vui lòng nhập chủ sở hữu nhà vì chủ hộ không đồng thời là chủ sở hữu")
        if (isCt01SubjectMinor()) {
            if (ct01Data.guardianName.isBlank()) return reject("Người dưới 18 tuổi cần họ tên cha, mẹ hoặc người giám hộ")
            if (!ct01Data.guardianCitizenId.matches(Regex("\\d{12}"))) return reject("Số định danh cha, mẹ hoặc người giám hộ phải gồm đúng 12 chữ số")
        }
        if (!validDate(ct01Data.signingDate)) return reject("Ngày ký phải là ngày hợp lệ theo định dạng DD/MM/YYYY")
        return true
    }

    private fun saveCt01Image() {
        collectCt01Form()
        if (!validateCt01()) return
        toast("Đang ghép 02 trang CT01 vào một hình…")
        Thread {
            runCatching {
                val directory = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir, "CT01")
                check(directory.exists() || directory.mkdirs())
                val file = File(directory, "CT01.png")
                val bitmap = Ct01Renderer.renderCombinedA4Bitmap(this, ct01Data, ct01DeclarantSignature, ct01OwnerSignature, ct01HeadSignature, ct01GuardianSignature)
                FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf("image/png"), null)
                publishToDownloads(file, "image/png")
            }.onSuccess { runOnUiThread { toast("Đã lưu CT01 hai trang chung trong một ảnh") } }
                .onFailure { runOnUiThread { toast("Không thể lưu ảnh CT01: ${it.message}") } }
        }.start()
    }


    private fun identityItem(caption: String, value: String, prominent: Boolean): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(identityCaption(caption))
        addView(TextView(this@MainActivity).apply {
            text = value
            textSize = if (prominent) 28f else 19f
            setTextColor(Color.rgb(27, 28, 37))
            typeface = if (prominent || caption == "HỌ VÀ TÊN") Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            setLineSpacing(dp(2).toFloat(), 1f)
            if (prominent) letterSpacing = .035f
        }, margins(ViewGroup.LayoutParams.WRAP_CONTENT, top = 5))
    }

    private fun identityPair(leftCaption: String, leftValue: String, rightCaption: String, rightValue: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        val left = identityItem(leftCaption, leftValue, false)
        val right = identityItem(rightCaption, rightValue, false)
        addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(9) })
        addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(9) })
    }

    private fun identityCaption(value: String) = TextView(this).apply {
        text = value
        textSize = 11f
        letterSpacing = .08f
        setTextColor(Color.rgb(143, 147, 159))
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }

    private fun vietnameseTitleCase(value: String): String {
        val locale = Locale("vi", "VN")
        return value.lowercase(locale).split(Regex("\\s+")).joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
        }
    }

    private fun showAppChrome() {
        contractWizardVisible = false
        identityResultVisible = false
        templateSelectionVisible = false
        ct01Visible = false
        topBar.visibility = View.VISIBLE
        bottomNavigation.visibility = View.VISIBLE
    }

    private fun formatQrDate(value: String): String = if (value.length == 8 && value.all(Char::isDigit))
        "${value.substring(0, 2)}/${value.substring(2, 4)}/${value.substring(4)}" else value

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            ct01Visible && ct01Step > 0 -> { persistCt01WizardStep(); ct01Step--; showCt01Wizard() }
            ct01Visible -> showTemplateSelection(lastScannedCitizen ?: emptyCitizen())
            contractWizardVisible && contractStep > 0 -> { persistContractWizardStep(); contractStep--; showContractWizard() }
            contractWizardVisible -> showTemplateSelection(lastScannedCitizen ?: emptyCitizen())
            templateSelectionVisible -> lastScannedCitizen?.let { showIdentityResult(it) } ?: super.onBackPressed()
            identityResultVisible -> showForm()
            else -> super.onBackPressed()
        }
    }

    private fun confirmReset() {
        AlertDialog.Builder(this).setTitle("Làm mới hợp đồng")
            .setMessage("Khôi phục dữ liệu mẫu và xóa chữ ký hiện tại?")
            .setPositiveButton("Làm mới") { _, _ ->
                val fresh = ContractData()
                contract.time = fresh.time; contract.date = fresh.date; contract.place = fresh.place
                contract.placeDetail = fresh.placeDetail; contract.ward = fresh.ward; contract.province = fresh.province
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
        barcodeScanner.close()
        galleryBarcodeScanner.close()
        tenantSignature?.recycle()
        landlordSignature?.recycle()
        ct01DeclarantSignature?.recycle()
        ct01OwnerSignature?.recycle()
        ct01HeadSignature?.recycle()
        ct01GuardianSignature?.recycle()
        super.onDestroy()
    }
}
