# Knowledge Transfer: Flutter Camera Recorder

Yeh document project ke Flutter aur Android native camera code ko samajhne ke
liye hai. Isme use hui libraries, Pigeon bridge, recording flow, aur common
changes ka practical overview diya gaya hai.

## 1. App ka high-level flow

1. Flutter app `CameraRecorderController` create karta hai aur Provider ke
   through screen ko state deta hai.
2. Controller `PigeonCameraService` ke zariye Android host se permission,
   Camera2 devices aur preview mangta hai.
3. Android `CameraManager` se woh cameras list karta hai jo Android Camera2
   expose karta hai. Is list mein built-in front/back ya supported external USB
   camera aa sakta hai.
4. User camera cards select karta hai. Selected camera IDs Android ko bheje
   jaate hain aur har camera ka preview Flutter texture par dikhaya jata hai.
5. **Start SOS monitoring** har selected camera ka rolling MP4 segment
   recording start karta hai. Purane, unused segments hata diye jaate hain.
6. **SOS** par pehle se available footage ko configured post-event footage ke
   saath jodkar har selected camera ke liye alag MP4 save hota hai.
7. Combined front/back video ek optional feature hai; current default
   configuration mein yeh off hai. USB camera individual recording ke liye
   front/back combination ka hissa nahi hai.

> Camera ko Android Camera2 expose karna zaroori hai. USB plug hona akela
> guarantee nahi hai ki Android device ko Camera2 camera ke roop mein dikhaega.
> Simultaneous capture ki limit phone, camera provider, USB power/adapter aur
> supported encoder par depend karti hai.

## 2. Native Android libraries: kya karti hain

### Android framework APIs

| API / classes | Is app mein kaam |
| --- | --- |
| `android.hardware.camera2.CameraManager` | Android se available camera IDs, camera characteristics aur Camera2 device access lena. |
| `CameraCharacteristics` | Camera ka facing (front/back/external), sensor orientation aur supported output sizes padhna. |
| `CameraDevice`, `CameraCaptureSession`, `CaptureRequest` | Camera kholna, preview/recording surfaces ke saath session banana aur repeating frame requests bhejna. |
| `android.media.MediaRecorder` | Har selected camera ke frames ko alag temporary MP4 segment mein H.264 video ke roop mein likhna. |
| `MediaExtractor` | Existing MP4 segments ya completed videos ka track, format aur encoded samples padhna. |
| `MediaMuxer` | Encoded video samples ko MP4 container mein likhna; segment samples ko ek SOS MP4 mein assemble karna. |
| `MediaCodec`, `MediaCodecInfo`, `MediaCodecList` | Optional combined video ke frames ko H.264 mein encode karne ke liye device encoder dhoondhna aur chalana. |
| `MediaFormat` | Video codec, frame size, frame rate, bitrate aur track format configure/read karna. |
| `MediaMetadataRetriever` | Completed individual videos ki duration/rotation padhna aur optional combined-video ke liye frames decode karna. |
| `Bitmap`, `Canvas`, `Paint`, `Matrix`, `Rect` | Optional combined frame banana: front/rear images ko rotate karke side-by-side draw karna. |
| `Surface`, `SurfaceTexture` | Camera frames ko recorder surface ya Flutter preview texture tak bhejna. |
| `Handler`, `HandlerThread` | Camera2 ke asynchronous callbacks ke liye dedicated camera thread chalana, UI thread ko block kiye bina. |
| `SystemClock` | Buffer/segment timing ke liye monotonic clock use karna, jo wall-clock time changes se nahi badalta. |
| `Environment`, `File` | App-specific Movies output directory aur temporary segment files manage karna. |
| `Manifest`, `PackageManager` | Camera permission ki declaration/grant status check karna. |
| `Log` | Camera discovery/capability aur runtime failures ko Android log mein record karna. |

### AndroidX

| Library | Is app mein kaam |
| --- | --- |
| `androidx.core:core` (`ContextCompat`) | Runtime camera permission status ko Android versions ke across check karna. |
| `androidx.core:core` (`ActivityCompat`) | Camera permission prompt request karna. |

### Kotlin standard/JDK APIs

| API | Is app mein kaam |
| --- | --- |
| Kotlin coroutines (`kotlinx-coroutines-android`) | Asynchronous permission, camera setup, segment rotation aur file processing ko suspend/parallel work mein chalana. |
| `Mutex`, `withLock` | Ek camera ke segment list/rotation par concurrent read-write ko safe rakhna. |
| `CoroutineScope`, `Job`, `Dispatchers`, `async`, `awaitAll` | Camera jobs start/cancel karna aur independent camera files ko parallel assemble karna. |
| `suspendCancellableCoroutine` | Callback-based Android APIs (jaise permission/open-camera callbacks) ko Kotlin suspend calls mein adapt karna. |
| `ConcurrentHashMap`, `AtomicInteger` | Camera resources/progress count ko concurrent tasks ke beech safe rakhna. |
| `ByteBuffer` | Encoded video samples aur YUV pixel planes ko process karna. |
| `SimpleDateFormat`, `Date`, `Locale`, `File` | Unique timestamped output filenames aur filesystem operations. |

## 3. Flutter aur bridge libraries

| Library / package | Is app mein kaam |
| --- | --- |
| Flutter Material (`flutter/material.dart`) | App widgets, buttons, camera cards, progress bar aur theme. |
| Flutter foundation (`ChangeNotifier`) | Controller state change par listening widgets ko notify karna. |
| `provider` | `CameraRecorderController` ko app widget tree mein provide karna aur screen se `watch/read` karna. |
| `flutter/services.dart` | Platform error (`PlatformException`) ko controller mein readable message ke roop mein handle karna. |
| Flutter Android embedding (`FlutterActivity`, `FlutterEngine`) | Android Activity aur Flutter engine ko jodna. |
| Flutter `TextureRegistry` | Android Camera2 `SurfaceTexture` ko Flutter texture ID dena, jise `Texture` widget preview mein dikhata hai. |

## 4. Pigeon kya hai aur kyun use kiya gaya hai?

**Pigeon Flutter team ka code generator hai.** Project mein developer API
contract Dart mein likhta hai; Pigeon us contract se type-safe Dart aur Kotlin
bindings generate karta hai. Generated code Flutter ke binary message
transport ke upar request/response bhejta hai.

Is app mein:

- API ka source of truth: `pigeons/camera_api.dart`
- Generated Dart side:
  `lib/src/platform/camera_api.g.dart`
- Generated Android/Kotlin side:
  `android/app/src/main/kotlin/com/example/camera_app/CameraApi.g.kt`
- Android implementation: `AndroidCameraHost`
- API ko Android activity se register karne wala code: `MainActivity`

Pigeon ke fayde:

- API methods aur data models ek jagah declare hote hain.
- Dart aur Kotlin dono taraf matching method signatures generate hote hain.
- Camera IDs, settings aur saved file paths typed models mein jaate hain.
- Manual `MethodChannel` string names aur manually encoded maps ki zaroorat kam
  hoti hai.
- API field/method badalne par compiler-generated bindings mismatch ko pakadne
  mein madad karte hain.

Pigeon **camera driver ya camera library nahi hai**; yeh sirf Flutter aur native
code ke beech communication bridge generate karta hai. Real camera kaam Android
Camera2/MediaRecorder APIs karte hain.

### Pigeon API update karne ka tareeqa

1. API/data model ko `pigeons/camera_api.dart` mein edit karein.
2. Project root se bindings dobara generate karein:

   ```powershell
   dart run pigeon --input pigeons\camera_api.dart
   ```

3. Generated `.g.dart` aur `.g.kt` ko manually edit na karein.
4. Android host implementation, Dart service/controller aur tests ko naye
   method/field ke mutabiq update karein.
5. `flutter analyze`, `flutter test`, aur `flutter build apk --debug` chalayein.

### Current host methods

| Method | Purpose |
| --- | --- |
| `requestCameraPermission()` | Android camera permission lena. |
| `listCameras()` | Android Camera2 ki available device list Flutter ko dena. |
| `setPreviewCameras(ids)` | Chune hue cameras ke preview open karna. |
| `startBuffering(ids, settings)` | Har selected camera ka rolling segment buffer start karna. |
| `getBufferingSeconds()` | SOS pre-event buffer mein available waqt batana. |
| `getSosProgress()` | Native SOS capture/assembly/optional composition ki progress dena. |
| `triggerSos()` | SOS clip(s) banana aur `cameraId` + `filePath` results dena. |
| `stopBuffering()` | Temporary, unsaved rolling buffer rok kar delete karna. |
| `releaseCameras()` | Native sessions, recorders aur camera resources release karna. |

## 5. Code ke main parts

| File | Responsibility |
| --- | --- |
| `lib/src/camera/capture_settings.dart` | Capture defaults aur combined-video feature flag ka single config. |
| `lib/src/camera/camera_service.dart` | Testable Dart service interface aur Pigeon adapter. |
| `lib/src/camera/camera_recorder_controller.dart` | Camera selection, buffer/SOS state, validation, progress aur recording history. |
| `lib/src/screens/camera_recorder_screen.dart` | Provider se state padhkar camera list, SOS controls aur progress dikhana. |
| `lib/src/widgets/camera_card.dart` | Ek camera ka preview, select checkbox aur saved path dikhana. |
| `lib/src/app.dart` | Provider/controller create karna aur Material app configure karna. |
| `android/app/src/main/kotlin/com/example/camera_app/MainActivity.kt` | Pigeon API register karna aur permission/activity lifecycle forward karna. |
| `android/app/src/main/kotlin/com/example/camera_app/AndroidCameraHost.kt` | Camera2, MediaRecorder, temporary buffers, SOS muxing aur optional composition. |
| `pigeons/camera_api.dart` | Pigeon API aur Dart/Kotlin ke beech shared data types. |

## 6. Single config file

Capture options `lib/src/camera/capture_settings.dart` mein rakhe jaate hain.
Current defaults:

| Setting | Default | Effect |
| --- | ---: | --- |
| Segment duration | 10 seconds | Rolling temporary segment ki target duration. |
| Pre-event maximum | 30 seconds | SOS se pehle kitna footage rakhna hai. |
| Post-event duration | 30 seconds | SOS ke baad kitni der capture chalta rahega. |
| Frame rate | 15 fps | Recorder ka requested frame rate. |
| Video bitrate | 1,500,000 bit/s | Individual camera MP4 ka requested bitrate. |
| Combined video | `false` | Side-by-side front/back encode off; opt-in ke liye `true` karein. |

`combinedVideoEnabled` ko `true` karne par Android pehle front/back individual
SOS MP4s complete karta hai, phir unse combined file banata hai. USB camera
apni individual recording karta hai; combined mode ka input front/back built-in
pair hai.

## 7. Tests aur device verification

- Flutter tests fake camera service use karte hain. Woh controller/UI flow ko
  verify karte hain, physical camera, USB adapter, Camera2 concurrency ya
  hardware encoder ko nahi.
- `flutter analyze`: Dart static analysis.
- `flutter test`: controller/widget tests.
- `flutter build apk --debug`: Dart + Android Kotlin compilation/build.
- Real device par camera list, preview, simultaneous camera support, audio/video
  file playback, USB power aur SOS duration alag se verify karna zaroori hai.

## 8. Important limitation

App wahi cameras use kar sakta hai jo Android Camera2 `CameraManager` expose
karta hai. Agar USB device Android system ke Camera2 provider mein appear nahi
hota, to yeh implementation us UVC device ko seedha access nahi karegi. Isi
tarah Realme stock camera app ka dual-view support third-party Camera2 API ko
same concurrent access dene ki guarantee nahi hai.
