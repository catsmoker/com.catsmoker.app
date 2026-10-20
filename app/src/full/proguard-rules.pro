# Full-variant rules (wired via proguardFile in the `full` flavor block).
# Never applies to playstore: Start.io/LSPosed/Xposed must not leak there.

# Start.io (formerly StartApp) SDK rules.
# The broad keep rule is often required by the SDK to function correctly due to reflection.
-keep class com.startapp.** {
    *;
}

-keep class com.truenet.** {
    *;
}

-dontwarn android.webkit.JavascriptInterface
-dontwarn com.startapp.**

# Keep LSPosed entrypoints and config names stable in release builds.
-keep class com.catsmoker.app.features.spoofdevice.root.LSPosedModule { *; }
-keep class com.catsmoker.app.shared.data.model.LSPosedConfig { *; }
# NativeSpoofChannel's JNI entry points are resolved by their exact Kotlin names
# (Java_com_catsmoker_app_features_spoofdevice_root_NativeSpoofChannel_*), so a
# renamed class would make every native setter an UnsatisfiedLinkError in release.
-keep class com.catsmoker.app.features.spoofdevice.root.NativeSpoofChannel { *; }

# Gson persists these spoof models by field name and generic signature
# (SpoofRepository reads StoreData with StoreData::class.java). The release dex
# otherwise carries neither, so ladder rungs deserialize as LinkedTreeMap —
# the first ladder touch then crashes with ClassCastException — while profile
# fields silently load empty. Narrow per-class keeps.
-keep class com.catsmoker.app.shared.data.repository.SpoofRepository$StoreData { *; }
-keep class com.catsmoker.app.shared.data.repository.SpoofRepository$ProfileEntry { *; }
-keep class com.catsmoker.app.shared.data.repository.SpoofRepository$RateCandidate { *; }
-keep class com.catsmoker.app.shared.data.model.DeviceProfile { *; }

-dontwarn de.robv.android.xposed.**
