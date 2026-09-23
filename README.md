# Hợp đồng thuê nhà

Ứng dụng Android Kotlin lập hợp đồng thuê nhà trực tiếp trên điện thoại hoặc máy tính bảng.

## Chức năng

- Nhập và chỉnh sửa thông tin hai bên cùng các điều khoản thuê.
- Quét QR trên CCCD bằng camera hoặc đọc từ ảnh có sẵn; dữ liệu xử lý cục bộ.
- Lưu, chọn và xóa hồ sơ người thuê trên thiết bị.
- Ký tay cho bên thuê và bên cho thuê.
- Xem trước hợp đồng A4, xuất PDF, lưu ảnh PNG và chia sẻ PDF.
- Giao diện responsive, tối ưu cho màn hình điện thoại và máy tính bảng.

## Build

Yêu cầu JDK 17, Android SDK 34 và Gradle 8.4.

```shell
gradle clean lintDebug assembleDebug
```

APK debug được tạo tại `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions cũng tự động build và lưu APK dưới dạng artifact sau mỗi lần push.
