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
import java.io.File
import java.io.FileOutputStream

object ContractRenderer {
    const val PAGE_WIDTH = 595
    const val PAGE_HEIGHT = 842

    fun renderBitmap(
        contract: ContractData,
        tenantSignature: Bitmap?,
        landlordSignature: Bitmap?,
        multiplier: Int = 2
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(PAGE_WIDTH * multiplier, PAGE_HEIGHT * multiplier, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(multiplier.toFloat(), multiplier.toFloat())
        draw(canvas, contract, tenantSignature, landlordSignature)
        return bitmap
    }

    fun createPdf(
        context: Context,
        contract: ContractData,
        tenantSignature: Bitmap?,
        landlordSignature: Bitmap?
    ): File {
        val document = PdfDocument()
        val page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, 1).create())
        draw(page.canvas, contract, tenantSignature, landlordSignature)
        document.finishPage(page)
        val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir, "HopDong")
        check(directory.exists() || directory.mkdirs()) { "Không thể tạo thư mục lưu hợp đồng" }
        val file = File(directory, "Hop_Dong_Thue_Nha_${safeFileStamp()}.pdf")
        FileOutputStream(file).use { document.writeTo(it) }
        document.close()
        return file
    }

    private fun draw(
        canvas: Canvas,
        contract: ContractData,
        tenantSignature: Bitmap?,
        landlordSignature: Bitmap?
    ) {
        canvas.drawColor(Color.WHITE)
        val regular = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = 8.3f; typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        }
        val bold = Paint(regular).apply { typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD) }
        val italic = Paint(regular).apply { typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC) }
        val title = Paint(bold).apply { textSize = 12.5f }
        val heading = Paint(bold).apply { textSize = 9.2f }
        val small = Paint(regular).apply { textSize = 7.4f }
        val margin = 48f
        val contentWidth = PAGE_WIDTH - margin * 2

        centerText(canvas, "CỘNG HÒA XÃ HỘI CHỦ NGHĨA VIỆT NAM", bold, 45f)
        centerText(canvas, "Độc lập - Tự do - Hạnh phúc", bold, 57f)
        canvas.drawLine(242f, 61f, 353f, 61f, regular)
        centerText(canvas, "HỢP ĐỒNG THUÊ NHÀ", title, 86f)

        var y = 108f
        y = paragraph(canvas, "Hôm nay, vào lúc ${contract.time}, ngày ${contract.date}, tại địa chỉ: ${contract.place}", margin, y, contentWidth, regular, 11f)
        y = text(canvas, "Chúng tôi gồm có:", margin, y + 1f, regular, 11f)
        y = text(canvas, "BÊN CHO THUÊ NHÀ (BÊN A):", margin, y + 1f, heading, 12f)
        y = personBlock(canvas, contract.landlord, margin + 8f, y, contentWidth - 8f, regular)
        y = text(canvas, "BÊN THUÊ NHÀ (BÊN B - ĐẠI DIỆN THUÊ):", margin, y + 1f, heading, 12f)
        y = personBlock(canvas, contract.tenant, margin + 8f, y, contentWidth - 8f, regular)
        y = paragraph(canvas, "Hai bên tự nguyện thỏa thuận và thống nhất ký kết hợp đồng thuê nhà với các điều khoản sau:", margin, y + 2f, contentWidth, italic.apply { typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC) }, 11f)

        val clauses = listOf(
            "1. Bên A đồng ý cho Bên B thuê một phần diện tích nhà tại địa chỉ: ${contract.place}",
            "2. Mục đích thuê: Sử dụng làm nơi ở cho Bên B (theo danh sách kê khai tạm trú đính kèm).",
            "3. Diện tích thuê: ${contract.area}.",
            "4. Thời hạn thuê nhà: ${contract.duration} kể từ ngày hợp đồng được ký kết.",
            "5. Giá tiền thuê: ${contract.monthlyRent}.",
            "6. Quyền đăng ký cư trú: Bên A đồng ý cho Bên B thuê nhà được quyền lưu trú và làm thủ tục đăng ký tạm trú tại địa chỉ nêu trên theo đúng quy định của pháp luật. Bên A cam kết không tranh chấp nhà và không có yêu cầu gì về quyền sở hữu tài sản của Bên A."
        )
        clauses.forEach { clause ->
            val clausePaint = if (clause.startsWith("6.")) regular else Paint(regular).apply { typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD) }
            y = paragraph(canvas, clause, margin, y, contentWidth, clausePaint, 10.8f)
        }
        y = paragraph(canvas, "Biên bản này được lập thành 02 bản có giá trị pháp lý như nhau, mỗi bên giữ 01 bản.", margin, y + 3f, contentWidth, regular, 11f)

        val signTop = maxOf(y + 18f, 606f)
        val leftCenter = 174f
        val rightCenter = 421f
        centerTextAt(canvas, "BÊN THUÊ NHÀ (Bên B)", heading, leftCenter, signTop)
        centerTextAt(canvas, "BÊN CHO THUÊ NHÀ (Bên A)", heading, rightCenter, signTop)
        centerTextAt(canvas, "(Ký, ghi rõ họ tên)", italic, leftCenter, signTop + 12f)
        centerTextAt(canvas, "(Ký, ghi rõ họ tên)", italic, rightCenter, signTop + 12f)
        drawSignature(canvas, tenantSignature, Rect(105, (signTop + 18).toInt(), 243, (signTop + 94).toInt()))
        drawSignature(canvas, landlordSignature, Rect(352, (signTop + 18).toInt(), 490, (signTop + 94).toInt()))
        centerTextAt(canvas, contract.tenant.name.uppercase(), Paint(bold).apply { textSize = 8.2f }, leftCenter, signTop + 112f)
        centerTextAt(canvas, contract.landlord.name.uppercase(), Paint(bold).apply { textSize = 8.2f }, rightCenter, signTop + 112f)

        canvas.drawText("Hợp đồng được tạo từ ứng dụng Hợp đồng thuê nhà", margin, 817f, small.apply { color = Color.rgb(110, 120, 138) })
        canvas.drawText("Trang 1/1", 505f, 817f, small)
    }

    private fun personBlock(canvas: Canvas, person: PersonData, x: Float, startY: Float, width: Float, paint: Paint): Float {
        var y = startY
        val rows = listOf(
            "- Họ và tên: ${person.name}",
            "- Sinh năm: ${person.birthDate}",
            "- Số Căn cước công dân: ${person.citizenId}",
            "- Ngày cấp: ${person.issueDate}; Nơi cấp: ${person.issuePlace}",
            "- Hộ khẩu thường trú: ${person.permanentAddress}",
            "- Là chủ sở hữu hợp pháp nhà ở tại địa chỉ: ${person.currentAddress.ifBlank { person.permanentAddress }}"
        )
        rows.forEach { y = paragraph(canvas, it, x, y, width, paint, 10.4f) }
        return y
    }

    private fun drawSignature(canvas: Canvas, bitmap: Bitmap?, destination: Rect) {
        if (bitmap == null) {
            val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(218, 225, 235); style = Paint.Style.STROKE; strokeWidth = 0.7f }
            canvas.drawRect(destination, border)
            return
        }
        val source = Rect(0, 0, bitmap.width, bitmap.height)
        canvas.drawBitmap(bitmap, source, destination, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    }

    private fun paragraph(canvas: Canvas, value: String, x: Float, y: Float, maxWidth: Float, paint: Paint, lineHeight: Float): Float {
        var cursor = y
        val words = value.split(Regex("\\s+"))
        var line = ""
        words.forEach { word ->
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(candidate) <= maxWidth) line = candidate
            else {
                canvas.drawText(line, x, cursor, paint)
                cursor += lineHeight
                line = word
            }
        }
        if (line.isNotEmpty()) {
            canvas.drawText(line, x, cursor, paint)
            cursor += lineHeight
        }
        return cursor
    }

    private fun text(canvas: Canvas, value: String, x: Float, y: Float, paint: Paint, lineHeight: Float): Float {
        canvas.drawText(value, x, y, paint)
        return y + lineHeight
    }

    private fun centerText(canvas: Canvas, value: String, paint: Paint, y: Float) = centerTextAt(canvas, value, paint, PAGE_WIDTH / 2f, y)

    private fun centerTextAt(canvas: Canvas, value: String, paint: Paint, centerX: Float, y: Float) {
        canvas.drawText(value, centerX - paint.measureText(value) / 2f, y, paint)
    }
}
