package com.example.cccdscanner

data class Ct01Member(
    var name: String = "",
    var birthDate: String = "",
    var gender: String = "",
    var citizenId: String = "",
    var relationship: String = "Cùng ở thuê"
)

data class Ct01Data(
    var authority: String = "Công an phường Bình Quới, Thành phố Hồ Chí Minh",
    var declarantName: String = "",
    var birthDate: String = "",
    var gender: String = "Nam",
    var citizenId: String = "",
    var phone: String = "",
    var email: String = "trandoconghuy@gmail.com",
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
    var signingPlace: String = "Thành phố Hồ Chí Minh",
    var signingDate: String = ""
)
