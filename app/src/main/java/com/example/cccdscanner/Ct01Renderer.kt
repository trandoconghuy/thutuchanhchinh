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
import kotlin.math.min

object Ct01Renderer {
    const val PAGE_WIDTH = 595
    const val PAGE_HEIGHT = 842
    const val A4_PRINT_WIDTH = 2480
    const val A4_PRINT_HEIGHT = 3508
    const val PAGE_COUNT = 2
    private const val LEFT = 85f
    private const val RIGHT = 57f

    private data class Fonts(val regular: Typeface, val bold: Typeface, val italic: Typeface)

    fun renderBitmap(context: Context, data: Ct01Data, declarantSignature: Bitmap?, ownerSignature: Bitmap?, headSignature: Bitmap?, guardianSignature: Bitmap?, multiplier: Int = 2): Bitmap {
        val gap = 14 * multiplier
        val bitmap = Bitmap.createBitmap(PAGE_WIDTH * multiplier, PAGE_HEIGHT * PAGE_COUNT * multiplier + gap, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(226, 232, 242))
        val fonts = fonts(context)
        repeat(PAGE_COUNT) { index ->
            canvas.save()
            canvas.translate(0f, (index * PAGE_HEIGHT * multiplier + index * gap).toFloat())
            canvas.scale(multiplier.toFloat(), multiplier.toFloat())
            drawPage(canvas, index + 1, data, declarantSignature, ownerSignature, headSignature, guardianSignature, fonts)
            canvas.restore()
        }
        return bitmap
    }

    fun renderCombinedA4Bitmap(context: Context, data: Ct01Data, declarantSignature: Bitmap?, ownerSignature: Bitmap?, headSignature: Bitmap?, guardianSignature: Bitmap?): Bitmap {
        val gap = 32
        val bitmap = Bitmap.createBitmap(A4_PRINT_WIDTH, A4_PRINT_HEIGHT * PAGE_COUNT + gap, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(226, 232, 242))
        val fonts = fonts(context)
        repeat(PAGE_COUNT) { index ->
            canvas.save()
            canvas.translate(0f, (index * (A4_PRINT_HEIGHT + gap)).toFloat())
            canvas.scale(A4_PRINT_WIDTH / PAGE_WIDTH.toFloat(), A4_PRINT_HEIGHT / PAGE_HEIGHT.toFloat())
            drawPage(canvas, index + 1, data, declarantSignature, ownerSignature, headSignature, guardianSignature, fonts)
            canvas.restore()
        }
        return bitmap
    }

    fun createPdf(context: Context, data: Ct01Data, declarantSignature: Bitmap?, ownerSignature: Bitmap?, headSignature: Bitmap?, guardianSignature: Bitmap?): File {
        val document = PdfDocument()
        val fonts = fonts(context)
        repeat(PAGE_COUNT) { index ->
            val page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, index + 1).create())
            drawPage(page.canvas, index + 1, data, declarantSignature, ownerSignature, headSignature, guardianSignature, fonts)
            document.finishPage(page)
        }
        val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir, "CT01")
        check(directory.exists() || directory.mkdirs()) { "Không thể tạo thư mục CT01" }
        val file = File(directory, "CT01.pdf")
        FileOutputStream(file).use { document.writeTo(it) }
        document.close()
        return file
    }

    private fun fonts(context: Context) = Fonts(
        ResourcesCompat.getFont(context, R.font.liberation_serif_regular) ?: Typeface.SERIF,
        ResourcesCompat.getFont(context, R.font.liberation_serif_bold) ?: Typeface.create(Typeface.SERIF, Typeface.BOLD),
        ResourcesCompat.getFont(context, R.font.liberation_serif_italic) ?: Typeface.create(Typeface.SERIF, Typeface.ITALIC)
    )

    private fun drawPage(canvas: Canvas, page: Int, data: Ct01Data, declarantSignature: Bitmap?, ownerSignature: Bitmap?, headSignature: Bitmap?, guardianSignature: Bitmap?, fonts: Fonts) {
        canvas.drawRect(0f, 0f, PAGE_WIDTH.toFloat(), PAGE_HEIGHT.toFloat(), Paint().apply { color = Color.WHITE })
        if (page == 1) drawForm(canvas, data, declarantSignature, ownerSignature, headSignature, guardianSignature, fonts) else drawNotes(canvas, fonts)
    }

    private fun drawForm(canvas: Canvas, data: Ct01Data, declarantSignature: Bitmap?, ownerSignature: Bitmap?, headSignature: Bitmap?, guardianSignature: Bitmap?, fonts: Fonts) {
        val p = paint(fonts.regular, 10f)
        val b = paint(fonts.bold, 10f)
        val small = paint(fonts.regular, 8f)
        val smallBold = paint(fonts.bold, 8f)
        val width = PAGE_WIDTH - LEFT - RIGHT
        var y = 57f
        drawCenter(canvas, "Mẫu CT01 ban hành kèm theo Thông tư số 116/2026/TT-BCA", y, small)
        y += 11f
        drawCenter(canvas, "ngày 29 tháng 6 năm 2026 của Bộ trưởng Bộ Công an", y, small)
        y += 24f
        drawCenter(canvas, "CỘNG HÒA XÃ HỘI CHỦ NGHĨA VIỆT NAM", y, b)
        y += 14f
        drawCenter(canvas, "Độc lập – Tự do – Hạnh phúc", y, b)
        canvas.drawLine(220f, y + 4f, 375f, y + 4f, Paint().apply { color = Color.BLACK; strokeWidth = .8f })
        y += 31f
        drawCenter(canvas, "TỜ KHAI THAY ĐỔI THÔNG TIN CƯ TRÚ", y, paint(fonts.bold, 14f))
        y += 25f
        y = drawWrapped(canvas, "Kính gửi(1): ${data.authority}", LEFT, y, width, p, 14f)
        y += 3f
        y = drawWrapped(canvas, "1. Họ, chữ đệm và tên khai sinh: ${data.declarantName}", LEFT, y, width, p, 14f)
        y = drawWrapped(canvas, "2. Ngày, tháng, năm sinh: ${data.birthDate}      3. Giới tính: ${data.gender}", LEFT, y, width, p, 14f)
        canvas.drawText("4. Số định danh cá nhân:", LEFT, y, p)
        drawDigitBoxes(canvas, data.citizenId, LEFT + 145f, y - 11f, 15f, 17f, smallBold)
        y += 23f
        y = drawWrapped(canvas, "5. Số điện thoại liên hệ: ${data.phone}      6. Email: ${data.email}", LEFT, y, width, p, 14f)
        y = drawWrapped(canvas, "7. Họ, chữ đệm và tên chủ hộ(2): ${data.headName}      8. Mối quan hệ với chủ hộ: ${data.relationshipToHead}", LEFT, y, width, p, 14f)
        canvas.drawText("9. Số định danh cá nhân của chủ hộ:", LEFT, y, p)
        drawDigitBoxes(canvas, data.headCitizenId, LEFT + 195f, y - 11f, 15f, 17f, smallBold)
        y += 23f
        y = drawWrapped(canvas, "10. Nội dung đề nghị(3): ${data.requestContent}", LEFT, y, width, p, 14f)
        y += 2f
        canvas.drawText("11. Những thành viên trong hộ gia đình cùng thay đổi:", LEFT, y, p)
        y += 8f

        val tableTop = y
        val headerHeight = 34f
        val rowHeight = 19f
        val cols = floatArrayOf(23f, 132f, 77f, 37f, 107f, 77f)
        val headers = arrayOf("TT", "Họ, chữ đệm và tên", "Ngày sinh", "Giới tính", "Số định danh cá nhân", "Quan hệ với chủ hộ")
        drawTableGrid(canvas, LEFT, tableTop, cols, headerHeight, rowHeight, 9)
        var x = LEFT
        headers.forEachIndexed { index, text ->
            drawHeaderCell(canvas, text, x, tableTop, cols[index], headerHeight, smallBold)
            x += cols[index]
        }
        repeat(9) { row ->
            val member = data.members.getOrNull(row)
            val values = if (member == null) arrayOf("${row + 1}", "", "", "", "", "") else arrayOf(
                "${row + 1}", member.name, member.birthDate, member.gender, member.citizenId, member.relationship
            )
            x = LEFT
            values.forEachIndexed { index, text ->
                drawCellText(canvas, text, x, tableTop + headerHeight + rowHeight * row, cols[index], rowHeight, small)
                x += cols[index]
            }
        }
        y = tableTop + headerHeight + rowHeight * 9 + 14f

        val signatureWidth = width / 4f
        val signatureTop = y
        val titles = arrayOf("Ý KIẾN CỦA CHỦ HỘ(4)", "Ý KIẾN CỦA CHỦ SỞ HỮU CHỖ Ở HỢP PHÁP(5)", "Ý KIẾN CỦA CHA HOẶC MẸ HOẶC NGƯỜI GIÁM HỘ(6)", "NGƯỜI KÊ KHAI(7)")
        val hideHeadEntry = data.declarantIsHead()
        val hideOwnerEntry = data.declarantIsOwner()
        val hideGuardianEntry = !data.requiresGuardian || data.declarantIsGuardian()
        val hideDeclarantEntry = data.declarantFillsAllSignatureRoles()
        val effectiveOwnerSignature = if (data.headIsLegalOwner) headSignature else ownerSignature
        val dateParts = data.signingDate.split('/')
        val dateText = if (dateParts.size == 3) "${data.signingPlace}, ngày ${dateParts[0]} tháng ${dateParts[1]} năm ${dateParts[2]}" else "${data.signingPlace}, ${data.signingDate}"
        repeat(4) { index -> drawFittedCentered(canvas, dateText, LEFT + signatureWidth * index, signatureTop, signatureWidth, fonts.italic, 6.2f, 4.5f) }
        titles.forEachIndexed { index, text -> drawWrappedCentered(canvas, text, LEFT + index * signatureWidth, signatureTop + 15f, signatureWidth, paint(fonts.bold, 7.4f), 8.6f, 3) }
        if (!hideHeadEntry) {
            drawWrappedCentered(canvas, data.headConsent, LEFT, signatureTop + 45f, signatureWidth, paint(fonts.regular, 6.2f), 7.5f, 3)
            headSignature?.let { drawSignature(canvas, it, LEFT + 16f, signatureTop + 72f, signatureWidth - 32f, 43f) }
            drawFittedCentered(canvas, data.headName, LEFT, signatureTop + 130f, signatureWidth, fonts.bold, 8f, 5.5f)
        }
        if (!hideOwnerEntry) {
            drawWrappedCentered(canvas, data.ownerConsent, LEFT + signatureWidth, signatureTop + 45f, signatureWidth, paint(fonts.regular, 6.2f), 7.5f, 3)
            effectiveOwnerSignature?.let { drawSignature(canvas, it, LEFT + signatureWidth + 16f, signatureTop + 72f, signatureWidth - 32f, 43f) }
            drawFittedCentered(canvas, data.legalOwnerName, LEFT + signatureWidth, signatureTop + 130f, signatureWidth, fonts.bold, 8f, 5.5f)
            if (data.legalOwnerCitizenId.isNotBlank()) drawFittedCentered(canvas, "Số định danh cá nhân: ${data.legalOwnerCitizenId}", LEFT + signatureWidth, signatureTop + 143f, signatureWidth, fonts.regular, 7f, 5.2f)
        }
        if (!hideGuardianEntry) {
            drawWrappedCentered(canvas, data.guardianConsent, LEFT + signatureWidth * 2, signatureTop + 45f, signatureWidth, paint(fonts.regular, 6.2f), 7.5f, 3)
            guardianSignature?.let { drawSignature(canvas, it, LEFT + signatureWidth * 2 + 16f, signatureTop + 72f, signatureWidth - 32f, 43f) }
            drawFittedCentered(canvas, data.guardianName, LEFT + signatureWidth * 2, signatureTop + 130f, signatureWidth, fonts.bold, 8f, 5.5f)
            if (data.guardianCitizenId.isNotBlank()) drawFittedCentered(canvas, "Số định danh cá nhân: ${data.guardianCitizenId}", LEFT + signatureWidth * 2, signatureTop + 143f, signatureWidth, fonts.regular, 7f, 5.2f)
        }
        if (!hideDeclarantEntry) {
            declarantSignature?.let { drawSignature(canvas, it, LEFT + signatureWidth * 3 + 16f, signatureTop + 72f, signatureWidth - 32f, 43f) }
            drawFittedCentered(canvas, data.declarantName, LEFT + signatureWidth * 3, signatureTop + 130f, signatureWidth, fonts.bold, 8f, 5.5f)
        }
    }

    private fun drawNotes(canvas: Canvas, fonts: Fonts) {
        val p = paint(fonts.regular, 7.7f)
        val b = paint(fonts.bold, 9f)
        val i = paint(fonts.italic, 7.5f)
        var y = 57f
        canvas.drawText("Chú thích:", LEFT, y, b)
        y += 16f
        val notes = listOf(
            "(1) Cơ quan đăng ký cư trú.",
            "(2) Trường hợp đăng ký thường trú, đăng ký tạm trú, tách hộ ghi thông tin chủ hộ gia đình mới.",
            "(3) Ghi rõ ràng, cụ thể nội dung đề nghị. Ví dụ: ghi chi tiết thông tin nơi đề nghị đăng ký thường trú hoặc nơi đề nghị đăng ký tạm trú hoặc nội dung đề nghị xác nhận thông tin về cư trú..... Trường hợp đăng ký thường trú, đăng ký tạm trú, gia hạn tạm trú, tách hộ mà địa giới hành chính đã có sự thay đổi theo quyết định của cơ quan có thẩm quyền thì ghi thông tin theo địa giới hành chính mới, đồng thời ghi chú địa giới hành chính theo giấy tờ, tài liệu chứng minh chỗ ở hợp pháp.",
            "(4) Áp dụng đối với các trường hợp quy định tại khoản 2 (Trừ trường hợp người dưới 6 tuổi đăng ký về với cha, mẹ, người giám hộ), khoản 3, khoản 5, khoản 6 Điều 20; khoản 1 Điều 25; điểm a khoản 1 Điều 26 Luật Cư trú và các trường hợp khác theo quy định pháp luật. Việc lấy ý kiến của chủ hộ được thực hiện theo các phương thức sau:",
            "a) Chủ hộ ghi rõ nội dung đồng ý và ký, ghi rõ họ tên vào Tờ khai.\nb) Chủ hộ xác nhận nội dung đồng ý thông qua ứng dụng định danh quốc gia hoặc các dịch vụ công trực tuyến khác.\nc) Chủ hộ có văn bản riêng ghi rõ nội dung đồng ý (văn bản này không phải công chứng, chứng thực).",
            "(5) Áp dụng đối với các trường hợp quy định tại khoản 2 (Trừ trường hợp người dưới 6 tuổi đăng ký về với cha, mẹ, người giám hộ), khoản 3, khoản 4, khoản 5, khoản 6 Điều 20; khoản 1 Điều 25 Luật Cư trú; điểm a khoản 1 Điều 26 Luật Cư trú (trường hợp người đứng đầu cơ sở trợ giúp xã hội quyết định chủ hộ) và các trường hợp khác theo quy định pháp luật. Việc lấy ý kiến của chủ sở hữu chỗ ở hợp pháp được thực hiện theo các phương thức sau:",
            "a) Chủ sở hữu chỗ ở hợp pháp ghi rõ nội dung đồng ý và ký, ghi rõ họ tên vào Tờ khai.\nb) Chủ sở hữu chỗ ở hợp pháp xác nhận nội dung đồng ý thông qua ứng dụng định danh quốc gia (VNeID) hoặc các dịch vụ công trực tuyến khác.\nc) Chủ sở hữu chỗ ở hợp pháp có văn bản riêng ghi rõ nội dung đồng ý (văn bản này không phải công chứng, chứng thực).",
            "Ghi chú: Trường hợp chủ sở hữu hợp chỗ ở hợp pháp gồm nhiều cá nhân, tổ chức thì phải có ý kiến đồng ý của tất cả các đồng sở hữu, tổ chức trừ trường hợp đã có thỏa thuận về việc cử đại diện có ý kiến đồng ý hoặc trường hợp có quy định khác; Trường hợp chủ sở hữu chỗ ở hợp pháp xác nhận nội dung đồng ý thông qua ứng dụng định danh quốc gia thì công dân phải kê khai thông tin về họ, chữ đệm, tên và số ĐDCN của chủ sở hữu chỗ ở hợp pháp. Trường hợp đăng ký thường trú theo quy định tại điểm a Khoản 2 Điều 20 Luật Cư trú mà chỗ ở hợp pháp có nhiều hơn một chủ sở hữu thì chỉ cần ý kiến đồng ý của ít nhất một chủ sở hữu.",
            "(6) Áp dụng đối với trường hợp người chưa thành niên, người hạn chế hành vi dân sự, người không đủ năng lực hành vi dân sự có thay đổi thông tin về cư trú. Việc lấy ý kiến của cha, mẹ hoặc người giám hộ được thực hiện theo các phương thức sau:",
            "a) Cha, mẹ hoặc người giám hộ ghi rõ nội dung đồng ý và ký, ghi rõ họ tên vào Tờ khai.\nb) Cha, mẹ hoặc người giám hộ xác nhận nội dung đồng ý thông qua ứng dụng định danh quốc gia (VNeID) hoặc các dịch vụ công trực tuyến khác.\nc) Cha, mẹ hoặc người giám hộ có văn bản riêng ghi rõ nội dung đồng ý (văn bản này không phải công chứng, chứng thực).",
            "(7) Trường hợp nộp trực tiếp người kê khai ký, ghi rõ họ, chữ đệm và tên vào Tờ khai; Trường hợp nộp qua cổng dịch vụ công hoặc ứng dụng định danh quốc gia thì người kê khai không phải ký vào mục này. Trường hợp người kê khai đồng thời là chủ hộ hoặc chủ sở hữu chỗ ở hợp pháp hoặc cha, mẹ, người giám hộ của người thay đổi thì người kê khai không phải ký vào các mục (4), (5), (6), (7).",
            "(8) Chỉ kê khai thông tin khi công dân đề nghị xác nhận nội dung đồng ý thông qua ứng dụng định danh quốc gia (VNeID)."
        )
        notes.forEach { note ->
            note.split('\n').forEach { line -> y = drawWrapped(canvas, line, LEFT, y, PAGE_WIDTH - LEFT - RIGHT, if (line.startsWith("Ghi chú")) i else p, 9.7f) }
            y += 3f
        }
    }

    private fun paint(typeface: Typeface, size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.typeface = typeface; textSize = size; color = Color.BLACK }
    private fun drawCenter(canvas: Canvas, text: String, y: Float, paint: Paint) = canvas.drawText(text, (PAGE_WIDTH - paint.measureText(text)) / 2f, y, paint)
    private fun drawRight(canvas: Canvas, text: String, right: Float, y: Float, paint: Paint) = canvas.drawText(text, right - paint.measureText(text), y, paint)
    private fun drawCenteredIn(canvas: Canvas, text: String, left: Float, y: Float, width: Float, paint: Paint) = canvas.drawText(text, left + (width - paint.measureText(text)) / 2f, y, paint)

    private fun drawFittedCentered(canvas: Canvas, text: String, left: Float, y: Float, width: Float, typeface: Typeface, preferredSize: Float, minimumSize: Float) {
        if (text.isBlank()) return
        val fitted = paint(typeface, preferredSize)
        while (fitted.textSize > minimumSize && fitted.measureText(text) > width - 7f) fitted.textSize -= .2f
        var value = text
        while (fitted.measureText(text) > width - 7f && value.isNotEmpty() && fitted.measureText("$value…") > width - 7f) value = value.dropLast(1)
        if (value != text) value = value.trimEnd() + "…"
        drawCenteredIn(canvas, value, left, y, width, fitted)
    }

    private fun drawWrapped(canvas: Canvas, text: String, x: Float, startY: Float, maxWidth: Float, paint: Paint, lineHeight: Float): Float {
        var y = startY
        text.split('\n').forEach { paragraph ->
            var line = ""
            paragraph.split(Regex("\\s+")).forEach { word ->
                val trial = if (line.isBlank()) word else "$line $word"
                if (paint.measureText(trial) <= maxWidth) line = trial else {
                    canvas.drawText(line, x, y, paint); y += lineHeight; line = word
                }
            }
            if (line.isNotBlank()) { canvas.drawText(line, x, y, paint); y += lineHeight }
        }
        return y
    }

    private fun drawDigitBoxes(canvas: Canvas, digits: String, x: Float, y: Float, boxWidth: Float, height: Float, paint: Paint) {
        val border = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = .6f }
        repeat(12) { index ->
            val left = x + index * boxWidth
            canvas.drawRect(left, y, left + boxWidth, y + height, border)
            digits.getOrNull(index)?.let { canvas.drawText(it.toString(), left + (boxWidth - paint.measureText(it.toString())) / 2f, y + 12f, paint) }
        }
    }

    private fun drawTableGrid(canvas: Canvas, x: Float, y: Float, cols: FloatArray, headerHeight: Float, rowHeight: Float, dataRows: Int) {
        val border = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = .65f }
        val total = cols.sum()
        val totalHeight = headerHeight + rowHeight * dataRows
        canvas.drawRect(x, y, x + total, y + totalHeight, border)
        var current = x
        cols.dropLast(1).forEach { current += it; canvas.drawLine(current, y, current, y + totalHeight, border) }
        canvas.drawLine(x, y + headerHeight, x + total, y + headerHeight, border)
        repeat(dataRows - 1) { row -> canvas.drawLine(x, y + headerHeight + rowHeight * (row + 1), x + total, y + headerHeight + rowHeight * (row + 1), border) }
    }

    private fun drawCellText(canvas: Canvas, text: String, x: Float, y: Float, width: Float, height: Float, paint: Paint) {
        val fitted = Paint(paint)
        while (fitted.textSize > 5.8f && fitted.measureText(text) > width - 4f) fitted.textSize -= .25f
        val value = if (fitted.measureText(text) <= width - 4f) text else text.take(18) + "…"
        canvas.drawText(value, x + (width - fitted.measureText(value)) / 2f, y + height / 2f + fitted.textSize / 3f, fitted)
    }

    private fun drawHeaderCell(canvas: Canvas, text: String, x: Float, y: Float, width: Float, height: Float, paint: Paint) {
        val fitted = Paint(paint).apply { textSize = 6.8f }
        val lines = mutableListOf<String>()
        var line = ""
        text.split(Regex("\\s+")).forEach { word ->
            val trial = if (line.isBlank()) word else "$line $word"
            if (fitted.measureText(trial) <= width - 4f) line = trial else { if (line.isNotBlank()) lines += line; line = word }
        }
        if (line.isNotBlank()) lines += line
        val lineHeight = 8f
        val start = y + (height - lines.size * lineHeight) / 2f + fitted.textSize
        lines.forEachIndexed { index, value -> canvas.drawText(value, x + (width - fitted.measureText(value)) / 2f, start + index * lineHeight, fitted) }
    }

    private fun drawMultilineCentered(canvas: Canvas, text: String, left: Float, top: Float, width: Float, paint: Paint, lineHeight: Float) {
        text.split('\n').forEachIndexed { index, line -> drawCenteredIn(canvas, line, left, top + index * lineHeight, width, paint) }
    }

    private fun drawWrappedCentered(canvas: Canvas, text: String, left: Float, top: Float, width: Float, paint: Paint, lineHeight: Float, maxLines: Int) {
        val lines = mutableListOf<String>()
        var line = ""
        for (word in text.split(Regex("\\s+"))) {
            val trial = if (line.isBlank()) word else "$line $word"
            if (paint.measureText(trial) <= width - 6f) line = trial else {
                if (line.isNotBlank()) lines += line
                line = word
                if (lines.size == maxLines - 1) break
            }
        }
        if (line.isNotBlank() && lines.size < maxLines) lines += line
        lines.forEachIndexed { index, value -> drawCenteredIn(canvas, value, left, top + index * lineHeight, width, paint) }
    }

    private fun drawSignature(canvas: Canvas, bitmap: Bitmap, x: Float, y: Float, width: Float, height: Float) {
        val scale = min(width / bitmap.width, height / bitmap.height)
        val targetW = bitmap.width * scale
        val targetH = bitmap.height * scale
        canvas.drawBitmap(bitmap, null, Rect((x + (width - targetW) / 2f).toInt(), (y + (height - targetH) / 2f).toInt(), (x + (width + targetW) / 2f).toInt(), (y + (height + targetH) / 2f).toInt()), Paint(Paint.ANTI_ALIAS_FLAG))
    }
}
