# Tether

A MUD client for Android. Black screen, colored text, one-line input, and a connection that
stays up with the screen off. Aliases and triggers per world or global, gags, and lock-screen
notify and reply.

## Build and install

Needs JDK 21 and the Android SDK.

```
./gradlew check                 # lint and unit tests
./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease  # app/build/outputs/apk/release/app-release.apk (R8-minified)
adb install -r <apk>
```

### Release signing

If `keystore.properties` exists at the repo root, release builds are signed with it:

```
storeFile=release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

`storeFile` is relative to the repo root. Without the file, release builds are signed with the
debug key, which is fine for sideloading but not for publishing. Switching keys means
uninstalling first. `keystore.properties`, `*.jks` and `*.keystore` are gitignored.

## Test MUD

`tools/testmud.py` is a tiny MUD for trying the client by hand (Python 3, stdlib only):

```
python3 tools/testmud.py [--port 4000] [--mccp] [--password secret] [--chatter 5]
```

- `--port`: port to listen on (default 4000)
- `--mccp`: offer MCCP2 compression
- `--password`: require this password (otherwise any works)
- `--chatter`: seconds between background chatter lines (default 5)

From the emulator, the host machine is `10.0.2.2`. Commands: `look`, `say <text>`,
`flood <n>`, `quit` (closes cleanly), `reset` (closes with a TCP reset).

## Docs

- `docs/design.html`: screens and behavior
- `docs/build-plan.html`: architecture, build phases and coverage
