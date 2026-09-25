package com.example.cccdscanner

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object Ct01DocxExporter {
    private fun xml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun create(context: Context, data: Ct01Data): File {
        val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir, "CT01")
        check(directory.exists() || directory.mkdirs())
        val file = File(directory, "CT01.doc")
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            entry(zip, "[Content_Types].xml", contentTypes)
            entry(zip, "_rels/.rels", relationships)
            entry(zip, "word/styles.xml", styles)
            entry(zip, "word/document.xml", document(data))
        }
        return file
    }

    private fun entry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun paragraph(text: String, bold: Boolean = false, center: Boolean = false, size: Int = 24, after: Int = 70): String =
        "<w:p><w:pPr>${if (center) "<w:jc w:val=\"center\"/>" else ""}<w:spacing w:after=\"$after\"/></w:pPr><w:r><w:rPr>${if (bold) "<w:b/>" else ""}<w:sz w:val=\"$size\"/><w:szCs w:val=\"$size\"/></w:rPr><w:t xml:space=\"preserve\">${xml(text)}</w:t></w:r></w:p>"

    private fun cell(text: String, width: Int, bold: Boolean = false): String {
        val content = xml(text).replace("\n", "</w:t><w:br/><w:t xml:space=\"preserve\">")
        return "<w:tc><w:tcPr><w:tcW w:w=\"$width\" w:type=\"dxa\"/><w:vAlign w:val=\"center\"/></w:tcPr><w:p><w:pPr><w:jc w:val=\"center\"/><w:spacing w:after=\"20\"/></w:pPr><w:r><w:rPr>${if (bold) "<w:b/>" else ""}<w:sz w:val=\"18\"/></w:rPr><w:t xml:space=\"preserve\">$content</w:t></w:r></w:p></w:tc>"
    }

    private fun signatureCell(date: String, title: String, consent: String, name: String, citizenId: String, width: Int): String {
        fun line(text: String, bold: Boolean = false, italic: Boolean = false, size: Int = 18, after: Int = 25): String =
            "<w:p><w:pPr><w:jc w:val=\"center\"/><w:spacing w:after=\"$after\"/></w:pPr><w:r><w:rPr>${if (bold) "<w:b/>" else ""}${if (italic) "<w:i/>" else ""}<w:sz w:val=\"$size\"/></w:rPr><w:t xml:space=\"preserve\">${xml(text)}</w:t></w:r></w:p>"
        val idLine = if (citizenId.isBlank()) "" else line("Số căn cước: $citizenId", false, false, 16)
        return "<w:tc><w:tcPr><w:tcW w:w=\"$width\" w:type=\"dxa\"/><w:vAlign w:val=\"top\"/></w:tcPr>" +
            line(date, false, true, 12, 55) + line(title, true, false, 17, 55) + line(consent, false, false, 15, 420) +
            line(name, true, false, 18, 30) + idLine + "</w:tc>"
    }

    private fun document(data: Ct01Data): String {
        val members = (0 until 9).joinToString("") { index ->
            val member = data.members.getOrNull(index)
            "<w:tr>${cell("${index + 1}", 449)}${cell(member?.name.orEmpty(), 2575)}${cell(member?.birthDate.orEmpty(), 1495)}${cell(member?.gender.orEmpty(), 729)}${cell(member?.citizenId.orEmpty(), 2084)}${cell(member?.relationship.orEmpty(), 1493)}</w:tr>"
        }
        val date = data.signingDate.split('/')
        val dateText = if (date.size == 3) "${data.signingPlace}, ngày ${date[0]} tháng ${date[1]} năm ${date[2]}" else "${data.signingPlace}, ${data.signingDate}"
        val notes = listOf(
            "(1) Cơ quan đăng ký cư trú.",
            "(2) Trường hợp đăng ký thường trú, đăng ký tạm trú, tách hộ ghi thông tin chủ hộ gia đình mới.",
            "(3) Ghi rõ ràng, cụ thể nội dung đề nghị. Ví dụ: ghi chi tiết thông tin nơi đề nghị đăng ký thường trú hoặc nơi đề nghị đăng ký tạm trú hoặc nội dung đề nghị xác nhận thông tin về cư trú..... Trường hợp đăng ký thường trú, đăng ký tạm trú, gia hạn tạm trú, tách hộ mà địa giới hành chính đã có sự thay đổi theo quyết định của cơ quan có thẩm quyền thì ghi thông tin theo địa giới hành chính mới, đồng thời ghi chú địa giới hành chính theo giấy tờ, tài liệu chứng minh chỗ ở hợp pháp.",
            "(4) Áp dụng đối với các trường hợp quy định tại khoản 2 (Trừ trường hợp người dưới 6 tuổi đăng ký về với cha, mẹ, người giám hộ), khoản 3, khoản 5, khoản 6 Điều 20; khoản 1 Điều 25; điểm a khoản 1 Điều 26 Luật Cư trú và các trường hợp khác theo quy định pháp luật. Việc lấy ý kiến của chủ hộ được thực hiện theo các phương thức sau:",
            "a) Chủ hộ ghi rõ nội dung đồng ý và ký, ghi rõ họ tên vào Tờ khai.",
            "b) Chủ hộ xác nhận nội dung đồng ý thông qua ứng dụng định danh quốc gia hoặc các dịch vụ công trực tuyến khác.",
            "c) Chủ hộ có văn bản riêng ghi rõ nội dung đồng ý (văn bản này không phải công chứng, chứng thực).",
            "(5) Áp dụng đối với các trường hợp quy định tại khoản 2 (Trừ trường hợp người dưới 6 tuổi đăng ký về với cha, mẹ, người giám hộ), khoản 3, khoản 4, khoản 5, khoản 6 Điều 20; khoản 1 Điều 25 Luật Cư trú; điểm a khoản 1 Điều 26 Luật Cư trú (trường hợp người đứng đầu cơ sở trợ giúp xã hội quyết định chủ hộ) và các trường hợp khác theo quy định pháp luật. Việc lấy ý kiến của chủ sở hữu chỗ ở hợp pháp được thực hiện theo các phương thức sau:",
            "a) Chủ sở hữu chỗ ở hợp pháp ghi rõ nội dung đồng ý và ký, ghi rõ họ tên vào Tờ khai.",
            "b) Chủ sở hữu chỗ ở hợp pháp xác nhận nội dung đồng ý thông qua ứng dụng định danh quốc gia (VNeID) hoặc các dịch vụ công trực tuyến khác.",
            "c) Chủ sở hữu chỗ ở hợp pháp có văn bản riêng ghi rõ nội dung đồng ý (văn bản này không phải công chứng, chứng thực).",
            "Ghi chú: Trường hợp chủ sở hữu hợp chỗ ở hợp pháp gồm nhiều cá nhân, tổ chức thì phải có ý kiến đồng ý của tất cả các đồng sở hữu, tổ chức trừ trường hợp đã có thỏa thuận về việc cử đại diện có ý kiến đồng ý hoặc trường hợp có quy định khác; Trường hợp chủ sở hữu chỗ ở hợp pháp xác nhận nội dung đồng ý thông qua ứng dụng định danh quốc gia thì công dân phải kê khai thông tin về họ, chữ đệm, tên và số ĐDCN của chủ sở hữu chỗ ở hợp pháp. Trường hợp đăng ký thường trú theo quy định tại điểm a Khoản 2 Điều 20 Luật Cư trú mà chỗ ở hợp pháp có nhiều hơn một chủ sở hữu thì chỉ cần ý kiến đồng ý của ít nhất một chủ sở hữu.",
            "(6) Áp dụng đối với trường hợp người chưa thành niên, người hạn chế hành vi dân sự, người không đủ năng lực hành vi dân sự có thay đổi thông tin về cư trú. Việc lấy ý kiến của cha, mẹ hoặc người giám hộ được thực hiện theo các phương thức sau:",
            "a) Cha, mẹ hoặc người giám hộ ghi rõ nội dung đồng ý và ký, ghi rõ họ tên vào Tờ khai.",
            "b) Cha, mẹ hoặc người giám hộ xác nhận nội dung đồng ý thông qua ứng dụng định danh quốc gia (VNeID) hoặc các dịch vụ công trực tuyến khác.",
            "c) Cha, mẹ hoặc người giám hộ có văn bản riêng ghi rõ nội dung đồng ý (văn bản này không phải công chứng, chứng thực).",
            "(7) Trường hợp nộp trực tiếp người kê khai ký, ghi rõ họ, chữ đệm và tên vào Tờ khai; trường hợp nộp qua cổng dịch vụ công hoặc ứng dụng định danh quốc gia thì người kê khai không phải ký vào mục này. Trường hợp người kê khai đồng thời là chủ hộ hoặc chủ sở hữu chỗ ở hợp pháp hoặc cha, mẹ, người giám hộ của người thay đổi thì người kê khai không phải ký vào các mục (4), (5), (6), (7).",
            "(8) Chỉ kê khai thông tin khi công dân đề nghị xác nhận nội dung đồng ý thông qua ứng dụng định danh quốc gia (VNeID)."
        ).joinToString("") { paragraph(it, false, false, 18, 55) }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
${paragraph("Mẫu CT01 ban hành kèm theo Thông tư số 116/2026/TT-BCA", false, true, 18)}
${paragraph("ngày 29 tháng 6 năm 2026 của Bộ trưởng Bộ Công an", false, true, 18, 160)}
${paragraph("CỘNG HÒA XÃ HỘI CHỦ NGHĨA VIỆT NAM", true, true, 24)}
${paragraph("Độc lập – Tự do – Hạnh phúc", true, true, 24, 220)}
${paragraph("TỜ KHAI THAY ĐỔI THÔNG TIN CƯ TRÚ", true, true, 30, 220)}
${paragraph("Kính gửi(1): ${data.authority}")}
${paragraph("1. Họ, chữ đệm và tên khai sinh: ${data.declarantName}")}
${paragraph("2. Ngày, tháng, năm sinh: ${data.birthDate}                 3. Giới tính: ${data.gender}")}
${paragraph("4. Số định danh cá nhân: ${data.citizenId}")}
${paragraph("5. Số điện thoại liên hệ: ${data.phone}                 6. Email: ${data.email}")}
${paragraph("7. Họ, chữ đệm và tên chủ hộ(2): ${data.headName}")}
${paragraph("8. Mối quan hệ với chủ hộ: ${data.relationshipToHead}")}
${paragraph("9. Số định danh cá nhân của chủ hộ: ${data.headCitizenId}")}
${paragraph("10. Nội dung đề nghị(3): ${data.requestContent}")}
${paragraph("11. Những thành viên trong hộ gia đình cùng thay đổi:")}
<w:tbl><w:tblPr><w:tblW w:w="8825" w:type="dxa"/><w:tblBorders><w:top w:val="single" w:sz="6"/><w:left w:val="single" w:sz="6"/><w:bottom w:val="single" w:sz="6"/><w:right w:val="single" w:sz="6"/><w:insideH w:val="single" w:sz="6"/><w:insideV w:val="single" w:sz="6"/></w:tblBorders></w:tblPr>
<w:tr>${cell("TT",449,true)}${cell("Họ, chữ đệm và tên",2575,true)}${cell("Ngày sinh",1495,true)}${cell("Giới tính",729,true)}${cell("Số định danh cá nhân",2084,true)}${cell("Quan hệ với chủ hộ",1493,true)}</w:tr>$members</w:tbl>
<w:tbl><w:tblPr><w:tblW w:w="8825" w:type="dxa"/></w:tblPr><w:tr>
${signatureCell(dateText,"Ý KIẾN CỦA CHỦ HỘ(4)",data.headConsent,data.headName,"",2206)}
${signatureCell(dateText,"Ý KIẾN CỦA CHỦ SỞ HỮU CHỖ Ở HỢP PHÁP(5)",data.ownerConsent,data.legalOwnerName,data.legalOwnerCitizenId,2206)}
${signatureCell(dateText,"Ý KIẾN CỦA CHA HOẶC MẸ HOẶC NGƯỜI GIÁM HỘ(6)",data.guardianConsent,data.guardianName,data.guardianCitizenId,2206)}
${signatureCell(dateText,"NGƯỜI KÊ KHAI(7)","",data.declarantName,"",2207)}
</w:tr></w:tbl>
<w:p><w:r><w:br w:type="page"/></w:r></w:p>${paragraph("Chú thích:", true, false, 22, 120)}$notes
<w:sectPr><w:pgSz w:w="11907" w:h="16840"/><w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1701" w:header="720" w:footer="720" w:gutter="0"/></w:sectPr>
</w:body></w:document>"""
    }

    private const val contentTypes = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/></Types>"""
    private const val relationships = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""
    private const val styles = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Times New Roman" w:hAnsi="Times New Roman" w:eastAsia="Times New Roman"/><w:sz w:val="24"/><w:lang w:val="vi-VN"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:line="276" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults></w:styles>"""
}
