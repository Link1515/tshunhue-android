# Tshunhue for Android

Kotlin + Jetpack Compose implementation of [the original Tshunhue project](../tshunhue), for browsing community-maintained reaction-image catalogs.

## Architecture

The Android structure retains the original ownership boundaries while using Android conventions:

| Original Swift project | Android implementation | Responsibility |
| --- | --- | --- |
| `Shared/Models` | `data/model` | Catalog schema, resolved frames, source records |
| `Shared/Services` | `data/remote`, `data/repository` | HTTPS fetching, validation, source persistence, synchronization |
| `Tshunhue/AppModel` | `ui/TshunhueViewModel` | Observable app state and user commands |
| `FrameQuerySession` / browse helpers | `domain/CatalogBrowser` | Scope resolution and caption/tag search |
| `Tshunhue/Views` | `ui/screens` | Compose browse, result, detail, and settings screens |

Remote catalog JSON is treated as untrusted: the app accepts HTTPS URLs only, validates schema-critical fields, limits document/image bytes and redirects, and prevents oversized image decoding.

## Open in Android Studio

Open this directory as a Gradle project. It targets Android API 35, supports Android 8.0+ (API 26), and requires JDK 17 for Gradle/Android Studio.
