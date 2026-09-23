package com.example.cccdscanner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PersonData(
    var name: String = "",
    var birthDate: String = "",
    var citizenId: String = "",
    var issueDate: String = "",
    var issuePlace: String = "Bộ Công an",
    var permanentAddress: String = "",
    var currentAddress: String = ""
) {
    fun toJson() = JSONObject().apply {
        put("name", name); put("birthDate", birthDate); put("citizenId", citizenId)
        put("issueDate", issueDate); put("issuePlace", issuePlace)
        put("permanentAddress", permanentAddress); put("currentAddress", currentAddress)
    }

    companion object {
        fun fromJson(json: JSONObject) = PersonData(
            json.optString("name"), json.optString("birthDate"), json.optString("citizenId"),
            json.optString("issueDate"), json.optString("issuePlace", "Bộ Công an"),
            json.optString("permanentAddress"), json.optString("currentAddress")
        )
    }
}

data class ContractData(
    var time: String = "20 giờ 00 phút",
    var date: String = "22 tháng 09 năm 2026",
    var place: String = "558/23 Bình Quới, KP 12, phường Bình Quới, Thành phố Hồ Chí Minh",
    var landlord: PersonData = PersonData(
        "HUỲNH CÔNG HÂN", "10/03/1983", "079083010463", "10/08/2021",
        "Cục Cảnh sát QLHC về TTXH", "558/23 Bình Quới, KP 12, phường Bình Quới, Thành phố Hồ Chí Minh",
        "558/23 Bình Quới, KP 12, phường Bình Quới, Thành phố Hồ Chí Minh"
    ),
    var tenant: PersonData = PersonData(
        "NGUYỄN HOÀNG QUỐC HUY", "11/12/2005", "082205003837", "20/10/2024",
        "Bộ Công an", "Tổ 2, Ấp Hội Gia, xã Mỹ Phong, thành phố Mỹ Tho, tỉnh Đồng Tháp",
        "558/23 Bình Quới, KP 12, phường Bình Quới, Thành phố Hồ Chí Minh"
    ),
    var area: String = "30 m2",
    var duration: String = "02 năm",
    var monthlyRent: String = "2.300.000 VNĐ/tháng"
)

class TenantStore(context: Context) {
    private val preferences = context.getSharedPreferences("tenant_library", Context.MODE_PRIVATE)

    fun load(): MutableList<PersonData> {
        val raw = preferences.getString("tenants", null) ?: return mutableListOf()
        return runCatching {
            val array = JSONArray(raw)
            MutableList(array.length()) { PersonData.fromJson(array.getJSONObject(it)) }
        }.getOrDefault(mutableListOf())
    }

    fun save(person: PersonData): Boolean {
        if (person.name.isBlank() || person.citizenId.isBlank()) return false
        val items = load()
        val index = items.indexOfFirst { it.citizenId == person.citizenId }
        if (index >= 0) items[index] = person.copy() else items.add(person.copy())
        persist(items)
        return true
    }

    fun delete(citizenId: String) {
        persist(load().filterNot { it.citizenId == citizenId })
    }

    private fun persist(items: List<PersonData>) {
        val array = JSONArray()
        items.forEach { array.put(it.toJson()) }
        preferences.edit().putString("tenants", array.toString()).apply()
    }
}

fun safeFileStamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
