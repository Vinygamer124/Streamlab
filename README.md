# Stream Composer (Android · Kotlin)

App phối nhanh live stream GTA 6 cho kênh **Vinny Gamer Official**.

## Luồng
1. **Tài nguyên** – chọn video game (vài tiếng), video reaction, nhạc Come back soon, (tuỳ chọn) PNG khung/social.
2. **Lịch live** – nhập giờ bắt đầu → giờ kết thúc; app gợi ý: mở màn Come back soon bao nhiêu phút, bao lâu nghỉ một lần, break nào là Come back soon / Lunch-Dinner reaction. Sửa tay từng block.
3. **Xuất** – H.264/H.265, bitrate, ETA + dung lượng dự đoán realtime. Chạy foreground service, không bị kill.

## Kỹ thuật
- Media3 Transformer 1.5.1: mỗi đoạn timeline là một `EditedMediaItem`. Xuất theo **từng phần ~6 phút** (xem mục dưới), mỗi phần một Transformer riêng.
- Contour lines: `render/Overlays.kt` – marching squares, trường độ cao tuần hoàn theo `loopSec` (20 s) → loop khít, không dùng video.
- Khung reaction: cửa sổ trong suốt x64–1463, y174–961 (1080p). Video reaction thu vào cửa sổ, contour phủ lên, khung phủ trên cùng.
- PNG 3 social phủ toàn bộ stream bằng effect cấp Composition.
- Chuyển cảnh menu GTA 6 → PS5 → Rest mode: nền là khung game cuối (làm mờ) ghép dưới lớp UI đã tách nền (`assets/rest/*.webp` + `meta.json`),
  đồng hồ PS5 vẽ bằng code (`render/RestOverlays.kt`, font Fira Sans trong `assets/fonts/clock.ttf`) theo giờ live; âm thanh chỉ gồm âm giao diện (`clips/rest_a.mp4`, `rest_b.mp4`).
- PS5 connecting/connected: `assets/clips/ps5_*.mp4`.
- **Màn chờ (loop 10 s, nhạc người dùng trải lên)**: `starting_soon.mp4` (mở màn, thanh tiến trình đầy dần), `be_right_back.mp4` (break giữa stream), `comeback.mp4` (Come back soon, dùng được cho cả hai), `thanks_watching.mp4` (kết thúc). Màn Xuất có chip chọn màn mở màn / màn break (mặc định Starting soon / Be right back).
- **Thanks for watching cuối stream (mặc định bật, 30 s, chỉnh 10–120 s)**: cắt bớt cuối đoạn game cuối rồi nối màn cảm ơn → tổng thời lượng không đổi. Không dùng chung với GTA crash (bật crash thì bỏ qua Thanks).
- **Âm thanh ambient màn crash**: `ps5_crash.mp4` (vào dần từ 0,45 s, giữ tiếng lỗi PS5 gốc) và `crash_hold.mp4` (loop 28 s khít đầu-cuối, crossfade 2 s) đều có ambient PS5 lấy từ video tham chiếu; ambient ở giây 8,5 của hai clip khớp nhau nên nối liền khi crash chuyển sang màn Report Problem.
- **Dung lượng**: GitHub chỉ nhận file upload ≤25 MB nên toàn bộ gói (zip) giữ dưới 25 MB. Clip trong `assets/clips` đều là HEVC (hvc1, keyframe mỗi 1 s, CRF 26–28) + audio AAC im lặng cùng định dạng; ảnh `assets/rest/*.webp` lossy q80 (giữ alpha). Thêm clip mới thì nén theo cách này:
  `ffmpeg -i in.mp4 -c:v libx265 -crf 28 -preset medium -pix_fmt yuv420p -tag:v hvc1 -x265-params keyint=30:min-keyint=30:scenecut=0 -c:a copy out.mp4`
- **GTA crash cuối stream (tuỳ chọn, mặc định tắt – màn Xuất)**: cắt `CRASH_TOTAL_MS` (9,78 s) cuối của đoạn game cuối rồi nối `FreezeSeg` (khung cuối giữ 1,28 s, âm thanh lặp 160 ms cuối → tiếng treo) + `CrashSeg` (`clips/ps5_crash.mp4` 8,5 s: tối dần → "Game or App Error" → "Report Problem", kèm âm thanh PS5; video dừng tại đây). Tổng thời lượng không đổi; điểm đóng băng nằm ~10 s trước giờ kết thúc, không dùng giây cuối của video game.
- **Nền crash mới**: `ps5_crash.mp4` đã được ghép lại trên nền loop (UI tách khỏi nền cũ bằng morphological opening; nút trắng giữ nguyên điểm ảnh). `clips/crash_hold.mp4` (28 s, nền loop khít đầu-cuối bằng crossfade 2 s, màn "Report Problem" tĩnh, im lặng) nối ngay sau crash để kéo dài màn lỗi. Vòng đầu bắt đầu ở giây 8,5 (`CRASH_HOLD_FIRST_START_MS`) nên nền liền mạch. Số phút kéo dài chỉnh bằng thanh trượt ở màn Xuất (0–10, mặc định 3); phần này lấy bớt từ cuối đoạn game nên tổng thời lượng không đổi.
- **UI**: Material 3 thuần (Scaffold, TopAppBar, NavigationBar, Card, Button, Chip, LinearProgressIndicator), không còn viền chấm/gradient/font tuỳ biến; theo chế độ sáng/tối hệ thống.
- **Nơi lưu file**: `Movies/StreamComposer/` (thư viện máy); các phần tạm nằm ở cache của app và bị xoá ngay sau khi ghép. Android 11+ cần cấp "Truy cập mọi tệp" (nút trong màn Xuất); chưa cấp thì tạm lưu trong data của app như cũ.

## Xuất theo phần + tự thử lại (sửa lỗi `AudioEncoder … c2.android.aac.encoder … Error 0x80000000`)
Lỗi cũ: xuất một mạch 1,5–3 tiếng, tới ~85% encoder AAC (phần mềm, dùng chung tiến trình `mediaswcodec` với mọi decoder) bị lỗi
`CodecException 0x80000000` ở `queueInputBuffer` → cả lần xuất chết, mất sạch. Lỗi này là lỗi nhất thời của codec, không nằm ở dữ liệu.

Cách sửa (`render/ChunkPlanner.kt`, `render/Mp4Merger.kt`, `render/ExportService.kt`):
- Timeline được cắt thành các phần ~6 phút (đoạn game dài cắt thành mảnh liền mạch). Không cắt giữa cụm nhạc, chuyển cảnh Rest mode, PS5 connecting/connected hay chuỗi đóng băng/crash.
- Mỗi phần chạy bằng một `Transformer` mới → codec được tạo lại hoàn toàn sau mỗi phần. Phần nào lỗi thì **thử lại đúng phần đó** (tối đa 3 lần, chờ 2 s rồi 6 s cho dịch vụ codec khởi động lại), không làm lại từ đầu.
- Phần xong được **ghép nối không mã hoá lại** (`MediaExtractor` → `MediaMuxer`) vào file cuối rồi xoá ngay, nên ổ đĩa chỉ cần thêm chỗ cho ~1 phần.
- Nếu một phần lỗi cả 3 lần, app đóng file cuối để **giữ lại phần đã xuất xong** (xem được) và báo rõ lỗi.
- Đầu ra âm thanh được ép 48 kHz (`SonicAudioProcessor` cấp Composition) để mọi phần cùng định dạng; lúc ghép có kiểm tra khớp video/âm thanh, lệch thì báo lỗi rõ thay vì ghép ra file hỏng.
- Timeline ngắn (1 phần) vẫn ghi thẳng ra file cuối như trước. Log lỗi từng phần: `adb logcat -s StreamComposer`.

## Tiến độ xuất & hiệu năng (bản sửa)
- `render/ExportTracker.kt`: Media3 chỉ trả tiến độ SỐ NGUYÊN %, nên tracker nội suy 0,1%, cho biết đang ở khối nào (Game / Reaction / Rest mode / PS5 / Come back), ETA tính theo tốc độ riêng từng loại đoạn, và lấy khung nguồn xem trước mỗi ~3 s (chỉ khi màn Xuất đang mở).
- Contour reaction mặc định chạy bằng **fragment shader** (`render/ContourEffect.kt`, `BaseGlShaderProgram`): mỗi pixel trong cửa sổ reaction tự tính khoảng cách tới đường đồng mức, không còn vẽ bitmap/upload texture ở CPU. Công tắc "Contour reaction bằng GPU" trong màn Xuất; tắt thì quay về bản CPU (`ContourOverlay`, làm mới `CONTOUR_FPS` = 10 khung/giây).
- Reaction ép về 30 fps bằng `FrameDropEffect` trước khi vào hiệu ứng.
- Rest mode: overlay nhận thời gian theo composition → `SegClock` quy về thời gian tương đối của đoạn (xem `RestOverlays.kt`).

## Build
CI: `.github/workflows/android.yml` (Actions → artifact APK debug).
Local: Android Studio Koala+ / JDK 17, hoặc `gradle :app:assembleDebug` (Gradle 8.9).

## Icon
Adaptive icon mới (`res/drawable/ic_fg.xml`, `ic_launcher_bg.xml`, `ic_mono.xml`): các clip trên timeline màu cam + đầu phát (playhead) có chấm đỏ LIVE, nền nâu đen có quầng cam. Có lớp `monochrome` cho biểu tượng theo chủ đề (Android 13+). Icon thông báo (`ic_notif.xml`) là bản đơn sắc cùng hình.

## Kiểm thử
`gradle :app:testDebugUnitTest` – `ChunkPlannerTest` kiểm tra tổng thời lượng, vị trí bắt đầu, không cắt giữa cụm nhạc/chuyển cảnh, mảnh game liền mạch, quy offset Rest/Crash.
`deploy.sh` chạy build + test trước khi push.

## Lưu ý
Code chưa được biên dịch trong môi trường tạo ra nó (và chưa chạy thử trên máy thật): `deploy.sh` biên dịch + chạy unit test trước khi push; việc xuất thật phải thử trên điện thoại. Các chỗ phụ thuộc phiên bản Media3 gom trong `render/ExportEngine.kt`;
nếu build báo lỗi API (ví dụ `InAppFragmentedMp4Muxer`), xoá dòng đó hoặc chỉnh phiên bản trong `app/build.gradle.kts`.
