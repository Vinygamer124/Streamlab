# Stream Composer (Android · Kotlin)

App phối nhanh live stream GTA 6 cho kênh **Vinny Gamer Official**.

## Luồng
1. **Tài nguyên** – chọn video game (vài tiếng), video reaction, nhạc Come back soon, (tuỳ chọn) PNG khung/social.
2. **Lịch live** – nhập giờ bắt đầu → giờ kết thúc; app gợi ý: mở màn Come back soon bao nhiêu phút, bao lâu nghỉ một lần, break nào là Come back soon / Lunch-Dinner reaction. Sửa tay từng block.
3. **Xuất** – H.264/H.265, bitrate, ETA + dung lượng dự đoán realtime. Chạy foreground service, không bị kill.

## Kỹ thuật
- Media3 Transformer 1.5.1: mỗi đoạn timeline là một `EditedMediaItem`; encoder ghi thẳng vào file MP4 (không file đệm).
- Contour lines: `render/Overlays.kt` – marching squares, trường độ cao tuần hoàn theo `loopSec` (20 s) → loop khít, không dùng video.
- Khung reaction: cửa sổ trong suốt x64–1463, y174–961 (1080p). Video reaction thu vào cửa sổ, contour phủ lên, khung phủ trên cùng.
- PNG 3 social phủ toàn bộ stream bằng effect cấp Composition.
- Chuyển cảnh menu GTA 6 → PS5 → Rest mode: nền là khung game cuối (làm mờ) ghép dưới lớp UI đã tách nền (`assets/rest/*.webp` + `meta.json`),
  đồng hồ PS5 vẽ bằng code (`render/RestOverlays.kt`, font Fira Sans trong `assets/fonts/clock.ttf`) theo giờ live; âm thanh chỉ gồm âm giao diện (`clips/rest_a.mp4`, `rest_b.mp4`).
- PS5 connecting/connected: `assets/clips/ps5_*.mp4`.

## Build
CI: `.github/workflows/android.yml` (Actions → artifact APK debug).
Local: Android Studio Koala+ / JDK 17, hoặc `gradle :app:assembleDebug` (Gradle 8.9).

## Lưu ý
Code chưa được biên dịch trong môi trường tạo ra nó. Các chỗ phụ thuộc phiên bản Media3 gom trong `render/ExportEngine.kt`;
nếu build báo lỗi API (ví dụ `InAppFragmentedMp4Muxer`), xoá dòng đó hoặc chỉnh phiên bản trong `app/build.gradle.kts`.
