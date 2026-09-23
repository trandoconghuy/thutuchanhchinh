package com.example.cccdscanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Environment
import androidx.core.content.res.ResourcesCompat
import java.io.File
import java.io.FileOutputStream

object ContractRenderer {
    const val PAGE_WIDTH = 595
    const val PAGE_HEIGHT = 842
    const val A4_PRINT_WIDTH = 2480
    const val A4_PRINT_HEIGHT = 3508
    const val PAGE_COUNT = 2

    private const val LEFT = 85f
    private const val RIGHT = 57f
    private const val TOP = 57f
    private const val CONTENT_WIDTH = PAGE_WIDTH - LEFT - RIGHT

    private data class Fonts(
        val regular: Typeface,
        val bold: Typeface,
        val italic: Typeface,
        val boldItalic: Typeface
    )

    fun renderBitmap(
        context: Context,
        contract: ContractData,
        tenantSignature: Bitmap?,
        landlordSignature: Bitmap?,
        multiplier: Int = 2
    ): Bitmap {
        val gap = 14 * multiplier
        val bitmap = Bitmap.createBitmap(PAGE_WIDTH * multiplier, PAGE_HEIGHT * PAGE_COUNT * multiplier + gap, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(226, 232, 242))
        val fonts = loadFonts(context)
        for (pageNumber in 1..PAGE_COUNT) {
            canvas.save()
            canvas.translate(0f, ((pageNumber - 1) * PAGE_HEIGHT * multiplier + (pageNumber - 1) * gap).toFloat())
            canvas.scale(multiplier.toFloat(), multiplier.toFloat())
            drawPage(canvas, pageNumber, contract, tenantSignature, landlordSignature, fonts)
            canvas.restore()
        }
        return bitmap
    }

    fun renderA4PrintBitmap(
        context: Context,
        pageNumber: Int,
        contract: ContractData,
        tenantSignature: Bitmap?,
        landlordSignature: Bitmap?
    ): Bitmap {
        require(pageNumber in 1..PAGE_COUNT)
        val bitmap = Bitmap.createBitmap(A4_PRINT_WIDTH, A4_PRINT_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(A4_PRINT_WIDTH / PAGE_WIDTH.toFloat(), A4_PRINT_HEIGHT / PAGE_HEIGHT.toFloat())
        drawPage(canvas, pageNumber, contract, tenantSignature, landlordSignature, loadFonts(context))
        return bitmap
    }

    fun createPdf(
        context: Context,
        contract: ContractData,
        tenantSignature: Bitmap?,
        landlordSignature: Bitmap?
    ): File {
        val document = PdfDocument()
        val fonts = loadFonts(context)
        for (pageNumber in 1..PAGE_COUNT) {
            val page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
            drawPage(page.canvas, pageNumber, contract, tenantSignature, landlordSignature, fonts)
            document.finishPage(page)
        }
        val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir, "HopDong")
        check(directory.exists() || directory.mkdirs()) { "Không thể tạo thư mục lưu hợp đồng" }
        val file = File(directory, "Hop_Dong_Thue_Nha_${safeFileStamp()}.pdf")
        FileOutputStream(file).use { document.writeTo(it) }
        document.close()
        return file
    }

    private fun loadFonts(context: Context) = Fonts(
        ResourcesCompat.getFont(context, R.font.liberation_serif_regular) ?: Typeface.SERIF,
        ResourcesCompat.getFont(context, R.font.liberation_serif_bold) ?: Typeface.create(Typeface.SERIF, Typeface.BOLD),
        ResourcesCompat.getFont(context, R.font.liberation_serif_italic) ?: Typeface.create(Typeface.SERIF, Typeface.ITALIC),
        ResourcesCompat.getFont(context, R.font.liberation_serif_bold_italic) ?: Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
    )

    private fun drawPage(
        canvas: Canvas,
        pageNumber: Int,
        contract: ContractData,
        tenantSignature: Bitmap?,
        landlordSignature: Bitmap?,
        fonts: Fonts
    ) {
        canvas.drawColor(Color.WHITE)
        if (pageNumber == 1) drawFirstPage(canvas, contract, fonts)
        else drawSecondPage(canvas, contract, tenantSignature, landlordSignature, fonts)
    }

    private fun drawFirstPage(canvas: Canvas, contract: ContractData, fonts: Fonts) {
        val regular = paint(fonts.regular, 12f)
        val bold = paint(fonts.bold, 12f)
        val italic = paint(fonts.italic, 12f)
        val boldItalic = paint(fonts.boldItalic, 12f)
        val title = paint(fonts.bold, 16f)

        var y = TOP + 10f
        centered(canvas, "CỘNG HÒA XÃ HỘI CHỦ NGHĨA VIỆT NAM", bold, y)
        y += 17f
        centered(canvas, "Độc lập - Tự do - Hạnh phúc", bold, y)
        underlineCentered(canvas, "Độc lập - Tự do - Hạnh phúc", bold, y)
        y += 19f
        centered(canvas, "———★———", regular, y)
        y += 32f
        centered(canvas, "HỢP ĐỒNG THUÊ NHÀ", title, y)
        y += 33f

        y = paragraph(canvas, "Căn cứ Bộ luật Dân sự nước Cộng hòa xã hội chủ nghĩa Việt Nam;", LEFT, y, CONTENT_WIDTH, italic, 18f, 5f, 0f, true)
        y = paragraph(canvas, "Căn cứ Luật Nhà ở và các văn bản pháp luật có liên quan;", LEFT, y, CONTENT_WIDTH, italic, 18f, 5f, 0f, true)
        y = paragraph(canvas, "Căn cứ nhu cầu và sự thỏa thuận của các bên,", LEFT, y, CONTENT_WIDTH, italic, 18f, 10f, 0f, true)
        y = paragraph(canvas, "Hôm nay, vào lúc ${contract.time}, ngày ${contract.date}, tại địa chỉ: ${contract.place}, chúng tôi gồm có:", LEFT, y, CONTENT_WIDTH, regular, 18f, 11f, 21f, true)

        y = line(canvas, "BÊN CHO THUÊ NHÀ (BÊN A):", LEFT, y, bold, 18f)
        y = personTable(canvas, contract.landlord, true, y, regular, bold)
        y += 10f
        y = line(canvas, "BÊN THUÊ NHÀ (BÊN B - ĐẠI DIỆN THUÊ):", LEFT, y, bold, 18f)
        y = personTable(canvas, contract.tenant, false, y, regular, bold)
        y += 10f
        y = paragraph(canvas, "Hai bên tự nguyện thỏa thuận và thống nhất ký kết Hợp đồng thuê nhà với các điều khoản sau đây:", LEFT, y, CONTENT_WIDTH, boldItalic, 18f, 10f, 0f, true)
        line(canvas, "Điều 1. Đối tượng và mục đích thuê", LEFT, y, bold, 18f)
    }

    private fun drawSecondPage(
        canvas: Canvas,
        contract: ContractData,
        tenantSignature: Bitmap?,
        landlordSignature: Bitmap?,
        fonts: Fonts
    ) {
        val regular = paint(fonts.regular, 12f)
        val bold = paint(fonts.bold, 12f)
        val italic = paint(fonts.italic, 12f)
        var y = TOP + 10f

        val area = contract.area.replace("m2", "m²", true)
        val areaWords = if (area.trim().startsWith("30")) " (ba mươi mét vuông)" else ""
        val duration = if (contract.duration.trim().startsWith("02")) "02 (hai) năm" else contract.duration
        val rent = contract.monthlyRent.replace("VNĐ", "đồng", true)
        val rentWords = if (rent.filter(Char::isDigit).startsWith("2300000")) " (Bằng chữ: Hai triệu ba trăm nghìn đồng trên tháng)" else ""

        y = paragraph(canvas, "Bên A đồng ý cho Bên B thuê một phần diện tích nhà tại địa chỉ: ${contract.place}, với diện tích thuê là $area$areaWords, để sử dụng làm nơi ở cho Bên B theo danh sách kê khai tạm trú đính kèm.", LEFT, y, CONTENT_WIDTH, regular, 18f, 10f, 0f, true)
        y += 10f
        y = line(canvas, "Điều 2. Thời hạn thuê", LEFT, y, bold, 18f)
        y = paragraph(canvas, "Thời hạn thuê nhà là $duration, kể từ ngày Hợp đồng này được hai bên ký kết.", LEFT, y, CONTENT_WIDTH, regular, 18f, 10f, 0f, true)
        y += 10f
        y = line(canvas, "Điều 3. Giá thuê và phương thức thanh toán", LEFT, y, bold, 18f)
        y = paragraph(canvas, "Giá thuê nhà là $rent$rentWords.", LEFT, y, CONTENT_WIDTH, regular, 18f, 10f, 0f, true)
        y += 10f
        y = line(canvas, "Điều 4. Quyền đăng ký cư trú", LEFT, y, bold, 18f)
        y = paragraph(canvas, "Bên A đồng ý cho Bên B được quyền lưu trú và làm thủ tục đăng ký tạm trú tại địa chỉ nêu trên theo đúng quy định của pháp luật. Bên A cam kết không tranh chấp nhà và không có yêu cầu gì về quyền sở hữu tài sản của Bên A đối với Bên B trong thời gian thuê.", LEFT, y, CONTENT_WIDTH, regular, 18f, 10f, 0f, true)
        y += 10f
        y = line(canvas, "Điều 5. Điều khoản thi hành", LEFT, y, bold, 18f)
        y = paragraph(canvas, "Hai bên cam kết thực hiện đúng các điều khoản đã thỏa thuận trong Hợp đồng. Trong quá trình thực hiện, nếu phát sinh vướng mắc, hai bên cùng bàn bạc, thương lượng trên tinh thần hợp tác và thiện chí.", LEFT, y, CONTENT_WIDTH, regular, 18f, 5f, 0f, true)
        y = paragraph(canvas, "Hợp đồng này được lập thành 02 (hai) bản, có giá trị pháp lý như nhau, mỗi bên giữ 01 (một) bản để thực hiện.", LEFT, y, CONTENT_WIDTH, regular, 18f, 13f, 0f, true)

        val cityDate = contract.date.replaceFirst(Regex("^ngày\\s+", RegexOption.IGNORE_CASE), "")
        centered(canvas, "TP. Hồ Chí Minh, ngày $cityDate", italic, y)
        y += 26f
        drawSignatureTable(canvas, y, contract, tenantSignature, landlordSignature, fonts)
        centered(canvas, "2", paint(fonts.regular, 10f), PAGE_HEIGHT - 31f)
    }

    private fun personTable(canvas: Canvas, person: PersonData, landlord: Boolean, startY: Float, regular: Paint, bold: Paint): Float {
        val labelX = LEFT + 10f
        val valueX = LEFT + CONTENT_WIDTH * .34f
        val valueWidth = LEFT + CONTENT_WIDTH - valueX
        val rows = listOf(
            "Họ và tên" to person.name.uppercase(),
            "Sinh năm" to person.birthDate,
            "Số Căn cước công dân" to person.citizenId,
            "Ngày cấp" to person.issueDate,
            "Nơi cấp" to person.issuePlace,
            "Hộ khẩu thường trú" to person.permanentAddress,
            (if (landlord) "Chủ sở hữu hợp pháp nhà tại" else "Là người có quyền lưu trú hợp pháp tại") to person.currentAddress.ifBlank { person.permanentAddress }
        )
        var y = startY
        rows.forEachIndexed { index, row ->
            val labelLines = wrap(row.first, regular, valueX - labelX - 7f)
            val valuePaint = if (index == 0) bold else regular
            val valueLines = wrap(row.second, valuePaint, valueWidth)
            val count = maxOf(labelLines.size, valueLines.size)
            labelLines.forEachIndexed { lineIndex, text -> canvas.drawText(text, labelX, y + lineIndex * 15f, regular) }
            valueLines.forEachIndexed { lineIndex, text -> canvas.drawText(text, valueX, y + lineIndex * 15f, valuePaint) }
            y += count * 15f + 2f
        }
        return y
    }

    private fun drawSignatureTable(
        canvas: Canvas,
        top: Float,
        contract: ContractData,
        tenantSignature: Bitmap?,
        landlordSignature: Bitmap?,
        fonts: Fonts
    ) {
        val bold = paint(fonts.bold, 12f)
        val italic = paint(fonts.italic, 12f)
        val leftCenter = LEFT + CONTENT_WIDTH * .25f
        val rightCenter = LEFT + CONTENT_WIDTH * .75f
        centeredAt(canvas, "BÊN THUÊ NHÀ (BÊN B)", bold, leftCenter, top)
        centeredAt(canvas, "BÊN CHO THUÊ NHÀ (BÊN A)", bold, rightCenter, top)
        centeredAt(canvas, "(Ký, ghi rõ họ tên)", italic, leftCenter, top + 16f)
        centeredAt(canvas, "(Ký, ghi rõ họ tên)", italic, rightCenter, top + 16f)
        drawSignature(canvas, tenantSignature, Rect((leftCenter - 72f).toInt(), (top + 22f).toInt(), (leftCenter + 72f).toInt(), (top + 92f).toInt()))
        drawSignature(canvas, landlordSignature, Rect((rightCenter - 72f).toInt(), (top + 22f).toInt(), (rightCenter + 72f).toInt(), (top + 92f).toInt()))
        centeredAt(canvas, contract.tenant.name.uppercase(), bold, leftCenter, top + 112f)
        centeredAt(canvas, contract.landlord.name.uppercase(), bold, rightCenter, top + 112f)
    }

    private fun drawSignature(canvas: Canvas, bitmap: Bitmap?, destination: Rect) {
        if (bitmap != null) canvas.drawBitmap(bitmap, Rect(0, 0, bitmap.width, bitmap.height), destination, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    }

    private fun paragraph(
        canvas: Canvas,
        value: String,
        x: Float,
        startY: Float,
        width: Float,
        paint: Paint,
        lineHeight: Float,
        after: Float,
        firstIndent: Float,
        justify: Boolean
    ): Float {
        val lines = wrapWithIndent(value, paint, width, firstIndent)
        var y = startY
        lines.forEachIndexed { index, line ->
            val indent = if (index == 0) firstIndent else 0f
            val drawX = x + indent
            val lineWidth = width - indent
            if (justify && index < lines.lastIndex && line.contains(' ')) justifiedLine(canvas, line, drawX, y, lineWidth, paint)
            else canvas.drawText(line, drawX, y, paint)
            y += lineHeight
        }
        return y + after
    }

    private fun wrapWithIndent(value: String, paint: Paint, width: Float, firstIndent: Float): List<String> {
        val result = mutableListOf<String>()
        var line = ""
        value.trim().split(Regex("\\s+")).forEach { word ->
            val candidate = if (line.isEmpty()) word else "$line $word"
            val available = width - if (result.isEmpty()) firstIndent else 0f
            if (line.isEmpty() || paint.measureText(candidate) <= available) line = candidate
            else { result += line; line = word }
        }
        if (line.isNotEmpty()) result += line
        return result
    }

    private fun wrap(value: String, paint: Paint, width: Float): List<String> = wrapWithIndent(value, paint, width, 0f)

    private fun justifiedLine(canvas: Canvas, line: String, x: Float, y: Float, width: Float, paint: Paint) {
        val words = line.split(' ')
        val wordsWidth = words.sumOf { paint.measureText(it).toDouble() }.toFloat()
        val gap = (width - wordsWidth) / (words.size - 1)
        var cursor = x
        words.forEachIndexed { index, word ->
            canvas.drawText(word, cursor, y, paint)
            cursor += paint.measureText(word) + if (index < words.lastIndex) gap else 0f
        }
    }

    private fun line(canvas: Canvas, value: String, x: Float, y: Float, paint: Paint, lineHeight: Float): Float {
        canvas.drawText(value, x, y, paint)
        return y + lineHeight
    }

    private fun centered(canvas: Canvas, value: String, paint: Paint, y: Float) = centeredAt(canvas, value, paint, PAGE_WIDTH / 2f, y)

    private fun centeredAt(canvas: Canvas, value: String, paint: Paint, centerX: Float, y: Float) {
        canvas.drawText(value, centerX - paint.measureText(value) / 2f, y, paint)
    }

    private fun underlineCentered(canvas: Canvas, value: String, paint: Paint, y: Float) {
        val width = paint.measureText(value)
        val left = PAGE_WIDTH / 2f - width / 2f
        canvas.drawLine(left, y + 2f, left + width, y + 2f, Paint(paint).apply { strokeWidth = .65f })
    }

    private fun paint(typeface: Typeface, size: Float) = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = Color.BLACK
        textSize = size
        this.typeface = typeface
    }
}
