# AnimeVietsub (dựng lại từ Animevietsub.cs3 v47)

Cấu trúc giống cs347. Xem AnimeVietsubProvider/src/main/kotlin/recloudstream/AnimeVietsubProvider.kt (dựng lại bằng jadx 1.5.6: đầy đủ trang chủ, tìm kiếm, chi tiết, loadLinks, giải mã playlist, interceptor; có cả WebView Cloudflare và ProviderDomainMigration; chưa port AbyssPlayer (mã chết, không được gọi)) và thư mục reverse/.


Repo plugin [Cloudstream3](https://github.com/recloudstream) (khung gốc từ cs347)

**⚠️ Make sure you check "Include all branches" when using this template**

 
## Getting started with writing your first plugin

Repo này gồm 1 plugin: AnimeVietsubProvider.

1. Mở build.gradle.kts ở thư mục gốc, sửa `YOUR_USER/animevietsub` thành repo GitHub của bạn
2. Familiarize yourself with the project structure. Most files are commented
3. Build or deploy your first plugin using:
   - Windows: `.\gradlew.bat AnimeVietsubProvider:make` or `.\gradlew.bat AnimeVietsubProvider:deployWithAdb`
   - Linux & Mac: `./gradlew AnimeVietsubProvider:make` or `./gradlew AnimeVietsubProvider:deployWithAdb`


## Granting All Files Access on Newer Android Devices

For local plugin testing, you need to grant the app "All Files Access" on newer Android devices (Android 11 and above). Here’s how to do it:

### Using ADB

* `adb shell appops set --uid PACKAGE_NAME MANAGE_EXTERNAL_STORAGE allow`
* Replace `PACKAGE_NAME` with the name of the package for the Cloudstream3 version you are using:
   - debug: `com.lagradost.cloudstream3.prerelease.debug`
   - prerelease: `com.lagradost.cloudstream3.prerelease`
   - stable: `com.lagradost.cloudstream3`

### Manually

1. **Open Settings**: Go to your device’s Settings menu.

2. **Navigate to Special Access**:
   - Tap on "Apps & notifications" or "Apps".
   - Select "Special app access" or "Special access".

3. **Select All Files Access**:
   - Tap on "All files access".
   - It may be under the three vertical dots menu towards the top of the screen.

4. **Grant Access to the App**: Find the app in the list and tap on it to toggle it, if it is not already enabled.

6. **Restart the App**: Close and reopen the app to apply the changes.


## License

Everything in this repo is released into the public domain. You may use it however you want with no conditions whatsoever


## Attribution

This template as well as the gradle plugin and the whole plugin system is **heavily** based on [Aliucord](https://github.com/Aliucord).
*Go use it, it's a great mobile discord client mod!*
