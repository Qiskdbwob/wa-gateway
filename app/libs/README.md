## `wagateway.aar` is generated, not committed

This directory must contain **`wagateway.aar`** — the gomobile binding of the
Go/`whatsmeow` gateway in `../../go-wagateway/`.

The AAR is intentionally **not** committed (it is a large binary that must be
rebuilt whenever `go-wagateway/**` changes). It is produced by the
`gateway-aar` job in `.github/workflows/build.yml`:

```bash
# what CI runs, from the go-wagateway directory
gomobile bind -target=android/arm64,android/amd64,android/arm -androidapi 24 \
  -o ../app/libs/wagateway.aar .
```

`app/build.gradle.kts` consumes it with:

```kotlin
implementation(files("libs/wagateway.aar"))
```

### The `.aar` is not enough on its own: unpack its `jni/` too

A local `.aar` given to Gradle as a file dependency only contributes `classes.jar`. Its
`jni/<abi>/libgojni.so` is silently ignored, which produces an APK that installs and runs but
whose gateway dies on the first call with `UnsatisfiedLinkError: libgojni.so not found` — and an
APK that is far smaller than it should be (the `.so` is ~26 MB per ABI). The native libraries
must therefore be unpacked next to the AAR:

```bash
unzip -o app/libs/wagateway.aar 'jni/*' -d app/libs/wagateway-jni
```

`app/build.gradle.kts` registers `libs/wagateway-jni/jni` as a `jniLibs` source directory and
fails the build with a readable message when the AAR is present but was never unpacked. CI does
the same unpack step before Gradle runs and verifies afterwards that the built APK really
contains `lib/<abi>/libgojni.so`.

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
