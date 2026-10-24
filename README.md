# Tether

A MUD client for Android.

- Stays connected with the screen off
- Full ANSI color, black background
- One-line input that leaves your keyboard alone
- Triggers, aliases, gags and timers, global or per world
- Notify and reply to tells from the lock screen

## Install

1. On your phone, open the [latest release](https://github.com/mmcdole/tether/releases/latest) and download the `.apk`.
2. Open it. If asked, allow your browser or Files app to install unknown apps.
3. Open Tether, tap **+**, and add your MUD's host and port.
4. On first connect, allow notifications and "Keep Tether connected?" so it stays connected with the screen off.

Updates install over the old version and keep your worlds and rules.

## Commands

Type these in the input line:

```
#alias {k} {kill %1}        alias for this world (#galias for all worlds)
#action {%1 tells you: %2} {say hi %1}
#gag {The fountain gurgles.}
#ticker {heal} {cast heal} {1}   timer: sends every second while on
#ticker heal off   #ticker heal on   #unticker heal (same as off)
#unalias k   #unaction {...}   #ungag {...}
#alias   #action   #gag   #ticker   lists them
look;say hi                 ; separates commands
10e   3n2w   eeee           speedwalk
```

Long-press output to select and copy. Pinch to change text size.

## Build

Needs JDK 21 and the Android SDK.

```
./gradlew check assembleRelease
```

Release builds are signed with `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) if present, otherwise the debug key. `tools/testmud.py` is a small local MUD for testing.
