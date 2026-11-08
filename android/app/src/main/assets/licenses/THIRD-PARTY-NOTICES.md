# Third-Party Components

This project uses the following components. Their respective upstream licenses apply; this project does not claim ownership of third-party code or the Codex product.

- .NET / ASP.NET Core / Windows Forms / System.Management: Microsoft and .NET contributors, MIT license. https://github.com/dotnet
- QRCoder 1.6.0: Raffael Herrmann and contributors, MIT license. https://github.com/codebude/QRCoder
- Kotlin and kotlinx.coroutines: JetBrains and contributors, Apache License 2.0. https://github.com/JetBrains/kotlin
- AndroidX, Jetpack Compose, Material Components: Android Open Source Project, Apache License 2.0. https://android.googlesource.com/platform/frameworks/support/
- OkHttp 4.12.0 and Okio: Square and contributors, Apache License 2.0. https://github.com/square/okhttp
- ZXing Android Embedded 4.3.0: Journey Mobile and contributors, Apache License 2.0. https://github.com/journeyapps/zxing-android-embedded
- ZXing core: ZXing authors, Apache License 2.0. https://github.com/zxing/zxing
- CommonMark Java 0.24.0, GFM tables and strikethrough extensions: Robin Stocker and contributors, BSD 2-Clause license. https://github.com/commonmark/commonmark-java
- JUnit and AndroidX Test are used for tests only, under their upstream EPL / Apache licenses.

Codex must be installed separately by the user. Tailscale is optional for local-network use and must be installed separately when used for remote access. No Codex desktop code, model credentials, Tailscale credentials, or upstream application bundles are included in this source archive.

License texts and runtime notices are included in `licenses/`. QRCoder's license is from the exact 1.6.0 NuGet repository commit; .NET notices are from the redistributed runtime packages. Apache-2.0.txt applies to the Apache-licensed Android components listed above.
