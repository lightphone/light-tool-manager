# Server

Server logic + Ktor HTTP wrapper for the Tool Manager frontend to
browse. Runs embedded inside LightOS (Android target) or standalone via
[serverrunner](../serverrunner) (JVM target).

## DataTree & DataViews

Everything the server serves comes from a tree of `DataView`s, each pairing a `DataViewSpec`
(the frontend-facing metadata — label, path, header text) with a `DataTree` (the thing that
actually does the work). A node is either:

- **`BranchDataTree`** — has children (`getChildren()`), no files of its own. Used for
  navigation levels, like the root page or a folder of tools.
- **`LeafDataTree`** — an "endpoint" that has files or dynamic data to (list/read/write/delete/rename). 
  These can be read/write only.

`RootDataTree` is what actually walks this: given a path, it consumes segments through branch
nodes (caching each `getChildren()` call with a TTL) until it hits a leaf, then delegates file
ops to that leaf. 

Specs come in a few flavors ([DataViewSpec.kt](../shared/src/commonMain/kotlin/com/thelightphone/toolmanager/DataViewSpec.kt)):

- **`RootViewSpec`** — the top of the tree, always paired with a branch.
- **`FileBrowserSpec`** — a standard file browser page (list/upload/download/delete).
- **`DropboxSpec`** — a single upload target, no browsing.
- **`ExportSpec`** — a single download target, no browsing.
- **`CustomSpec`** — a raw data endpoint for a tool that wants to fetch/post its own payloads
  instead of using the file browser UI. Always hidden from tree navigation - they don't display any UI.

A few ready-made `DataTree` implementations live under
[datatree](./src/jvmSharedMain/kotlin/com/thelightphone/toolmanager/datatree): `FileDataTree`
(real filesystem I/O), `CachingDataTree` (TTL-caches an arbitrary source — e.g. Android's
`ContentResolver`, see `ContentResolverDataTree`), `EncryptingDataTree` (encrypts small text
files at rest), and `StaticBranchProvider` (a fixed, precomputed list of children).

## Third-party tools (discovered via LightFileProvider)

A third-party cool can expose its own page(s) in the tree without the server hardcoding anything
about it. On the client side (see the SDK's `client` module), the tool:

1. Declares a `<provider>` using `com.thelightphone.sdk.LightFileProvider`, with a unique
   `android:authorities` and a `<meta-data android:name="com.thelightphone.toolmanager.TOOL_MANAGER_PROVIDER" android:value="true" />`
   tag so the server can find it.
2. Sets `LightFileProvider.manifest` to a lambda producing a `ClientToolManifest`, which is a serialized version of it's `DataTree`.

`DiscoveredToolsBranchProvider` (Android-only, in this module) scans
installed packages for that `<meta-data>` tag via `PackageManager`, calls each provider's
`ContentProvider.call()` to fetch and decode its `ClientToolManifest`, and rebuilds the real
`DataView` tree by wrapping every leaf in a `ContentResolverDataTree`.

## Authentication

Every `/api/*` route (except `/api/pair`) requires a signed request — see the interceptor in
`Application.module()` and `ToolManagerAuth.verifySignature()`. Auth is pluggable through
`ToolManagerAuth`. Passing `auth = null` runs the server wide open, which is only meant for
local dev.

The default implementation, `TotpToolManagerAuth`, gives out keys two ways:

- **`primaryKey`** — generated once per process and baked into the app's own URL as a `#`
  fragment, so the frontend running on the same device picks it up automatically.
- **Pairing** — `POST /api/pair` with a 6-digit TOTP code (rotates every ~30s off a
  per-process secret) mints a new, separate key. Minted keys are encrypted and persisted to
  disk so they survive a restart, and expire after `keyValidityDuration` (2 days by default).

A key is never sent to the server directly. Instead, each request is signed using
`HMAC-SHA256(key, "$method\n$path\n$timestampMillis")`, hex-encoded, and sent as either
`X-Tm-Signature`/`X-Tm-Timestamp` headers or `sig`/`ts` query params. The server checks the
signature against every currently-valid key and rejects anything outside a 5-minute window. 
Given that this server will generally have very few clients, this is low overhead and protects
against replay attacks.

## HTTPS via local-ip.co

Browsers don't like to let users access pages served over http, and we can't
use a normal https configuration since the server is running on people's phones. We use 
[local-ip.co](https://local-ip.co) to sidestep this and provide a smoother user experience: 
it runs wildcard DNS for `*.my.local-ip.co`
that resolves straight to whatever IP is embedded in the subdomain
(`192-168-1-5.my.local-ip.co` → `192.168.1.5`), paired with a real, publicly-trusted wildcard
cert for that same domain. So the server can listen on
`https://<your-lan-ip-with-dashes>.my.local-ip.co:$HTTPS_PORT` and the browser sees a fully
valid HTTPS origin. Every byte still goes straight over the LAN, never through local-ip.co's servers. 
See `getLocalIpAddress()` in
[Main.kt](../serverrunner/src/main/kotlin/com/thelightphone/toolmanager/Main.kt) (JVM) and
`getHttpsUrl()` in `ToolManagerServiceAndroid` for where the domain gets built, and
`SslConfig` for fetching/caching/refreshing the cert+key and turning them into a `KeyStore` for
Ktor's `sslConnector`.

**Note that local-ip.co's private key is public and every server using their certs shares it.**
This means a motivated attacker could technically intercept and decrypt traffic _if they have access
to the network that the Light Phone and connecting PC are on_. 

**Remember: this server is designed to speak to clients on the SAME NETWORK**
