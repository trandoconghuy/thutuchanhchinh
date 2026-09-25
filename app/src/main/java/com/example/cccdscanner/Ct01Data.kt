package com.example.cccdscanner

data class Ct01Member(
    var name: String = "",
    var birthDate: String = "",
    var gender: String = "",
    var citizenId: String = "",
    var relationship: String = "Cùng ở thuê",
    var relationshipToDeclarant: String = ""
)

data class Ct01Data(
    var filingMode: String = "Cho chính tôi",
    var procedureType: String = "Đăng ký tạm trú",
    var authority: String = "",
    var declarantName: String = "",
    var birthDate: String = "",
    var gender: String = "Nam",
    var citizenId: String = "",
    var phone: String = "",
    var email: String = "",
    var oldPermanentAddress: String = "",
    var province: String = "",
    var district: String = "",
    var ward: String = "",
    var newAddressDetail: String = "",
    var newAddress: String = "",
    var housingSituation: String = "",
    var householdType: String = "",
    var temporaryUntil: String = "",
    var headIsLegalOwner: Boolean = true,
    var applicantAgreed: Boolean = false,
    var headAgreed: Boolean = false,
    var ownerAgreed: Boolean = false,
    var requiresGuardian: Boolean = false,
    var guardianAgreed: Boolean = false,
    var headName: String = "",
    var relationshipToHead: String = "Chủ hộ",
    var headCitizenId: String = "",
    var requestContent: String = "",
    var members: MutableList<Ct01Member> = mutableListOf(),
    var headConsent: String = "",
    var legalOwnerName: String = "",
    var legalOwnerCitizenId: String = "",
    var ownerConsent: String = "",
    var guardianName: String = "",
    var guardianCitizenId: String = "",
    var guardianConsent: String = "",
    var consentMethod: String = "Ký trực tiếp trên tờ khai",
    var signingPlace: String = "",
    var signingDate: String = ""
)

private fun sameCitizen(firstId: String, secondId: String): Boolean =
    firstId.length == 12 && firstId == secondId

fun Ct01Data.declarantIsHead(): Boolean =
    householdType == "Lập hộ tạm trú riêng" || sameCitizen(citizenId, headCitizenId)

fun Ct01Data.declarantIsOwner(): Boolean =
    sameCitizen(citizenId, legalOwnerCitizenId) || (headIsLegalOwner && declarantIsHead())

fun Ct01Data.declarantIsGuardian(): Boolean = sameCitizen(citizenId, guardianCitizenId)

fun Ct01Data.declarantFillsAllSignatureRoles(): Boolean =
    requiresGuardian && declarantIsHead() && declarantIsOwner() && declarantIsGuardian()
