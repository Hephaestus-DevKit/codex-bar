# AI Quota local Android build

Base: https://github.com/Hephaestus-DevKit/codex-bar at a618d5897509b0da3f9adc21ef85f67afe045bf5.
Local app ID: com.local.aquota (debug: com.local.aquota.debug).
Version: 1.1.4-local (10005). Min API 26; compile/target API 36.

Requirements: JDK 21, Android SDK platform 36/build-tools 36.0.0, Internet access for Gradle dependencies. Set JAVA_HOME and ANDROID_HOME for your machine.

```sh
./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest assembleRelease lintRelease --no-daemon --max-workers=2
```

Release output is unsigned: app/build/outputs/apk/release/app-release-unsigned.apk.
Use zipalign and apksigner with your own signing key. The supplied APK uses a dedicated local key; that private key and password are deliberately not part of this source archive.

Device tests use synthetic credentials only. Install debug APK and app-debug-androidTest.apk, allow notifications, then run:

```sh
adb shell am instrument -w -r com.local.aquota.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Run dualNotificationsHaveIndependentIdentityAndPromotionRequests separately for two synthetic quota notifications; run combinedChipKeepsBothQuotasVisibleAndLogoutCannotRepostIt for the combined fallback. The API 37 emulator promoted both separate notifications but displayed only one chip in both portrait and landscape. Default mode therefore combines percentages in OpenAI/Claude order, e.g. 80/65; stale or failed values become ?. Users can turn this mode off to request separate chips on their own OS. No release demo mode, remote credential import or test account backdoor is included. Live provider sign-in is not verified by these tests.

User-started foreground monitoring polls about once per minute, subject to Android scheduling and provider limits. Two notifications request promotion; the OS controls actual chip visibility, layout and text truncation. See the accompanying Hebrew security report and device results for tested behavior and limitations.

The original MIT license and attribution remain in LICENSE.

Quota notifications have only a provider title and Stop action, without body or expanded text. The existing channel defaults to LOW importance, no sound, vibration, lights or badge. LOW is the lowest importance that permits native chip promotion; MIN/minimized hides the chip. Android controls notification ordering and may prioritize promoted ongoing notifications; the app cannot pin a row to the bottom. Existing user channel preferences are preserved.
