# Some notes on how to build on Android:


## Building

Build debug from the command line: gradlew assembleDebug.

To build a signed apk from the command line, set up
~/.gradle/gradle.properties
to include:

```
RELEASE_STORE_FILE=/home/yourname/.config/AndroidKeys (or wherever)
RELEASE_STORE_PASSWORD=keystore-password
RELEASE_KEY_ALIAS=key0
RELEASE_KEY_PASSWORD=key-password
```

then run:

```
gradlew assembleRelease
```

The apk should show up in
```
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

## To test it in the emulator:

```
$ emulator -list-avds
Pixel_3a_API_30_x86
$ emulator @Pixel_3a_API_30_x86 &
```

Make sure you're using the adb installed with the build tools,
not just any old adb that can talk to your phone. Then:

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

To run it, you need to know the activity name, not just the app name.

``adb shell dumpsys package com.shallowsky.feedviewer``
shows ```com.shallowsky.feedviewer/.MainActivity```
sp then:

```
adb shell am start -n com.shallowsky.feedviewer/.MainActivity
```

