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
    var issuePlace: String = "",
    var permanentAddress: String = "",
    var currentAddress: String = "",
    var gender: String = ""
) {
    fun toJson() = JSONObject().apply {
        put("name", name); put("birthDate", birthDate); put("citizenId", citizenId)
        put("issueDate", issueDate); put("issuePlace", issuePlace)
        put("permanentAddress", permanentAddress); put("currentAddress", currentAddress)
        put("gender", gender)
    }

    companion object {
        fun fromJson(json: JSONObject) = PersonData(
            json.optString("name"), json.optString("birthDate"), json.optString("citizenId"),
            json.optString("issueDate"), json.optString("issuePlace"),
            json.optString("permanentAddress"), json.optString("currentAddress"), json.optString("gender")
        )
    }
}

data class ContractData(
    var time: String = "",
    var date: String = "",
    var place: String = "",
    var landlord: PersonData = PersonData(),
    var tenant: PersonData = PersonData(),
    var area: String = "",
    var duration: String = "",
    var monthlyRent: String = ""
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
