# iOS integration host and renderer checks

The default app now opens Trail's shared screen with native location/recording,
trail refresh/cache, report/photo/export handlers, map selection and contour
controls. It remains a development host: Apple signing and real iPhone behavior
are unverified. See [iOS packaging and limitations](../../ios/README.md).

`--metal-probe` selects the original synthetic renderer fixture. Other explicit
self-test flags exercise native persistence, maps, trails, layer toggles and the
shared legend. Test recording storage/preferences are separate from normal use;
synthetic local contour archives are bundled only into simulator packages.

On an ARM64 Mac with the workflow's Xcode, JDK 17 and Android SDK toolchains,
prepare the offline seed and build the framework/app pairs from the repo root:

```sh
python -m pip install shapely==2.1.2 Pillow==11.3.0
python tools/prepare_maps --output experiments/ios-map/build/mapAssets
./gradlew :shared:linkDebugFrameworkIosArm64 \
  :shared:linkDebugFrameworkIosSimulatorArm64 :shared:iosSimulatorArm64Test \
  :iosProbe:linkReleaseFrameworkIosSimulatorArm64 :iosProbe:linkReleaseFrameworkIosArm64
bash experiments/ios-map/build_probe_app simulator release
bash experiments/ios-map/build_probe_app device release
python ios/package_device
python experiments/ios-map/smoke_simulator.py
```

Both app hosts use Kotlin/Native Release frameworks and Swift `-O`.
The simulator app is ad-hoc signed without an Apple account. The device app and
Trail 2 IPA remain unsigned. These framework dependencies support ARM64 devices
and Apple Silicon simulators; an Intel Mac cannot run this simulator build.
The host requires iOS 18.5 because of the linked rendering dependency.

The iOS smoke workflow runs actual Metal pixel checks plus recording/export/
report/photo, offline map, trail refresh/visibility, four-basemap/cache, remote
contour range/cache, bundled contour/relaunch and legend checks. Inner app ZIPs
preserve executable permissions. Frameworks, the unsigned IPA, logs and screenshots
are retained in the `Trail-iOS-simulator-check` Actions artifact.
