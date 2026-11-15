// Dựng lại theo cấu trúc cs347. Thông tin lấy từ manifest.json của file .cs3 gốc:
// name = Animevietsub, version = 47, pluginClassName = recloudstream.AnimeVietsubPlugin

version = 47

cloudstream {
    language = "vi"
    description = "AnimeVietsub - Xem anime vietsub"
    authors = listOf("unknown") // .cs3 không lưu tên tác giả, hãy sửa lại

    /**
     * 0: Down, 1: OK, 2: Slow, 3: Beta only
     */
    status = 1

    tvTypes = listOf("Anime", "Cartoon", "Movie")

    iconUrl = "https://animevietsub.tv/favicon.ico" // đoán, hãy kiểm tra lại
}

android {
    defaultConfig {
        minSdk = 21
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
