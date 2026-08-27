# Tshunhue for Android

Tshunhue is an Android app for searching, browsing, and sharing reaction images from community-maintained catalogs. “Tshun-hue” means “spring flowers” in Taiwanese.

This project is an Android implementation written with Kotlin and Jetpack Compose. Its catalog design and usage follow the original SwiftUI project, [rschiang/tshunhue](https://github.com/rschiang/tshunhue), which supports macOS, iPhone, iPad, and an optional keyboard extension.

## Features

- Add and sync compatible image catalog sources.
- Browse images by source, category, and subsection, and search their captions and tags.
- Copy or share images, and keep favorites and recent items.
- Use Tshunhue Keyboard to search on-device catalogs with selected text or the current input line.
- Refresh catalogs manually or on a schedule while preserving the last successfully synchronized data.

## Catalogs

Tshunhue can read image catalogs that conform to the original project's [catalog schema](https://github.com/tshunlitiann/schema). A catalog contains an index and one or more image categories; categories may be divided into subsections for easier browsing.

Catalogs do not need to be hosted in Git repositories. The app checks the configured source URLs directly for updates and downloads their contents. Add only trusted sources: the app accepts HTTPS URLs only, validates required catalog fields, limits document and image sizes, and restricts redirects.

The original project maintains [Tshun-li̍t-iánn](https://github.com/tshunlitiann), a community-contributed metadata repository with catalog-format and contribution information.

## Privacy

Source settings, downloaded catalogs, image cache, favorites, and recent items are stored locally on the device. The app does not collect analytics, advertising identifiers, telemetry, contacts, or entered text.

When the app synchronizes a catalog or downloads an image, it connects directly to the source you configured. That host may receive ordinary network information, such as your IP address and User-Agent. Keyboard queries are performed against on-device catalogs unless you choose an image that needs to be downloaded.

## Open in Android Studio

Open this directory in Android Studio as a Gradle project.

- Compile and target Android API: 35
- Minimum supported Android version: 8.0 (API 26)
- Required JDK for Gradle and Android Studio: 17

## Original Project and License

This Android version is based on [rschiang/tshunhue](https://github.com/rschiang/tshunhue), the original project by Poren Chiang. This project uses the same license as the original project: the [GNU General Public License v3.0](https://www.gnu.org/licenses/gpl-3.0.html). Comply with the applicable license terms when using, distributing, or modifying derivative work.
