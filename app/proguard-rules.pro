# Unhook release rules (pure Java 8, no reflection-heavy SDKs)

# Keep SQLCipher's JNI surface intact.
-keep class net.zetetic.** { *; }

# ML weights are plain arrays; nothing to strip, but keep the package stable
# in case we later persist named layers.
-keep class com.unhook.app.ml.** { *; }

# Room ships its own consumer rules; nothing else needed for M1.
