# Trail 2 on iOS

The shared Kotlin/Compose app is hosted by `experiments/ios-map/app`; native
recording, location, files and map adapters live in `ios/TrailPlatform`. Android
remains the phone-tested production app. iOS is currently a development build.

The iOS workflow builds ARM64 simulator and device apps without Apple credentials,
runs native/shared tests and simulator rendering/handler checks, and publishes
`Trail-iOS-simulator-check` as a GitHub Actions artifact. That artifact contains:

- `TrailIosProbe-simulator.zip`: simulator app, test bundle identity.
- `TrailIosProbe-device-unsigned.zip`: unsigned device host, test identity.
- `Trail2-device-unsigned.ipa`: unsigned development device package, app identifier
  `mbligh.trail`, display name **Trail 2**, Android version name and workflow build
  number. Uses the existing horse icon. This is not an App Store submission archive.

The iOS app minimum is **18.5**, matching the linked renderer's ICU object. The
former 15.5 probe target was unverified and emitted a deployment-target warning.
Physical iPhone operation is still unverified. The minimum can be lowered only
after replacing/rebuilding that dependency and validating the older OS.

`ios/package_device` packages the existing compiled device host. It verifies the
Mach-O device platform/minimum, source plist, icon, background-location declaration,
absence of simulator contour fixtures, unchanged executable bytes and ZIP execute
permissions. It leaves the source app intact. Both simulator and device hosts use optimized Kotlin/Native Release frameworks
and Swift `-O`. The complete simulator suite exercises the optimized app.
`TrailBuildConfiguration=release` in the built plist records the variant; the IPA
packager refuses a Debug host. `build_probe_app` defaults to Release and accepts
an explicit `debug` second argument for development after linking Debug frameworks.

The IPA needs Apple provisioning and signing before installation. No signing
certificates, profiles, Apple account, App Store Connect setup or TestFlight upload
are configured. Device installation and distribution remain a separate step.
Real hardware is needed to validate GPS accuracy, permission prompts, background
recording/lock-screen behavior, camera hardware and the final system share flow.
Simulator handler tests do not establish these physical-device behaviors.

To reproduce on an ARM64 Mac with the workflow's Xcode and JDK toolchains, follow
`.github/workflows/ios-smoke.yml`; prepare the offline seed bundle first, link both
`iosProbe` Release frameworks, then run `build_probe_app` for simulator/device followed by
`python ios/package_device`. Pillow is used to render the existing Android vector
icon into an asset catalog; no separate iOS artwork is maintained.
