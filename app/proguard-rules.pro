# Add project specific ProGuard rules here.
# Keep defaults from proguard-android-optimize.txt.

# --- JNI / native ---
# libstreamy_lame calls into com.streamy.app.audio.Mp3Encoder by name
# (Java_com_streamy_app_audio_Mp3Encoder_native*). Keep the class and its native
# method names so R8 doesn't rename/strip them in a minified release build.
-keep class com.streamy.app.audio.Mp3Encoder { *; }
-keepclasseswithmembernames class * { native <methods>; }

# --- Kotlin metadata / coroutines (safe defaults) ---
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# ponytail: release builds currently ship with isMinifyEnabled=false. Before flipping it on,
# run a minified release build on a device through the full broadcast path and confirm the
# native MP3 encoder + Room + coroutines still work; add more keep rules as needed.
