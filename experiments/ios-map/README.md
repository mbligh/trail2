# iOS shared-renderer probe

This separate fixture app proves that Trail's actual shared Compose map layers
and live-position overlay render using the iOS Metal backend. It has synthetic
geometry and test artwork, blocks all network resources, and does not acquire GPS,
record real tracks, or implement the full Trail UI. The Android app is independent.

On an Apple Silicon Mac with current Xcode, JDK 17 and the Android SDK configured:

```sh
./gradlew :shared:linkDebugFrameworkIosArm64 \
  :shared:linkDebugFrameworkIosSimulatorArm64 :shared:iosSimulatorArm64Test \
  :iosProbe:linkDebugFrameworkIosSimulatorArm64
bash experiments/ios-map/build_simulator_app
python -m pip install Pillow==11.3.0
python experiments/ios-map/smoke_simulator.py
```

`build/TrailIosProbe.app` is a simulator app; ad-hoc signing requires no Apple
account. The static frameworks in `shared/build/bin/` are separate device and
simulator slices. The dependency publishes only ARM64 variants, so an Intel Mac
cannot run this simulator build. An iPhone build/distribution will need an actual
iOS app host, platform services and Apple signing setup in a later stage.

The `iOS shared framework and Metal simulator smoke test` workflow runs the same
commands on a macOS ARM64 runner and retains screenshots, pixel counts, logs,
frameworks and the simulator application. Its pixel assertions test actual GPU
output rather than accepting an empty map surface as a successful build.
