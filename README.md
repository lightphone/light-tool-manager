# File Manager

A Kotlin Multiplatform file browser with a Compose Multiplatform web frontend and a Ktor server backend. The server can be embedded in an Android app with mDNS discovery, allowing files on the device to be browsed, uploaded, downloaded, renamed, and deleted from any browser on the local network.

## Modules

- **[shared](./shared)** - Common data models (`Entry`, `DirectoryMeta`, `DownloadRequest`, etc.) shared across all modules.
- **[server](./server)** - Ktor server module with the `DataProvider` abstraction, `RootDataProvider` routing, `FileDataProvider` for filesystem access, and HTTP API routes. Targets JVM and Android.
  - **Android extras** - `ThumbnailDataProvider` (image/video thumbnail generation with LRU cache), `FileManagerServiceAndroid` (embedded server wrapper with JmDNS/mDNS registration).
- **[composeApp](./composeApp)** - Compose Multiplatform web frontend (JS target). Provides a file browsing UI with directory navigation, image/video thumbnail previews, multi-select download (ZIP), and file upload.
  - Note: there is an active Android build, but only to enable Compose previews in Android Studio. Most functionality is stubbed out. Don't use.
- **[serverrunner](./serverrunner)** - Standalone JVM entry point for running the server locally with sample directories.

## Running Locally

Build and run the server with the bundled frontend:

```shell
./gradlew :serverrunner:run
```

The frontend is automatically built and bundled into the server resources.

## Publishing the Android Library

// TODO publish to real maven repo

To publish the server module (with bundled frontend) to mavenLocal:

```shell
./gradlew :server:publishToMavenLocal
```

## API Endpoints
(implemented in [Application.kt](server/src/jvmSharedMain/kotlin/com/thelightphone/filemanager/Application.kt)
| Method | Path                        | Description                                    |
|--------|-----------------------------|------------------------------------------------|
| GET    | `/api/ping`                 | Health check                                   |
| GET    | `/api/root`                 | List root directories                          |
| GET    | `/api/meta/{path}`          | Get directory metadata (read-only status)      |
| GET    | `/api/files/{path}`         | List directory entries (paginated)             |
| GET    | `/api/download/{path}`      | Download a single file                         |
| GET    | `/api/thumbnail/{path}`     | Get image/video thumbnail                      |
| GET    | `/api/writecheck/{path}`    | Check if a path is writable                    |
| POST   | `/api/upload/{path}`        | Upload a file                                  |
| POST   | `/api/notify/{path}`        | Notify after uploads (e.g. trigger media scan) |
| POST   | `/api/rename/{path}`        | Rename a file or directory                     |
| DELETE | `/api/files/{path}`         | Delete a file or directory                     |
| POST   | `/api/download`             | Create bulk download token                     |
| GET    | `/api/download-zip/{token}` | Stream bulk download as ZIP                    |
