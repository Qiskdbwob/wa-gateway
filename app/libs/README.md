## `wagateway.aar` is generated, not committed

This directory must contain **`wagateway.aar`** — the gomobile binding of the
Go/`whatsmeow` gateway in `../../go-wagateway/`.

The AAR is intentionally **not** committed (it is a large binary that must be
rebuilt whenever `go-wagateway/**` changes). It is produced by the
`gateway-aar` job in `.github/workflows/build.yml`:

```bash
# what CI runs, from the go-wagateway directory
gomobile bind -target=android/arm64,android/amd64 -androidapi 24 \
  -o ../app/libs/wagateway.aar .
```

`app/build.gradle.kts` consumes it with:

```kotlin
implementation(files("libs/wagateway.aar"))
```

### Building locally

1. Install Go 1.26+, the Android SDK/NDK and gomobile:
   ```bash
   go install golang.org/x/mobile/cmd/gobind@latest
   go install golang.org/x/mobile/cmd/gomobile@latest
   gomobile init
   ```
2. Run the bind command above from `go-wagateway/`.
3. Then build the app (`gradle assembleDebug`).

If `app/libs/wagateway.aar` is missing, the Kotlin sources will not compile
(`unresolved reference: wagateway`).
