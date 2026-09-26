# Morsecode R8 rules.

# NanoHTTPD reflects on nothing, but keep its public surface so the WebShare
# server keeps working after shrinking (§7.1).
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**

# Play Services Nearby Connections (§11.3).
-keep class com.google.android.gms.nearby.** { *; }
-dontwarn com.google.android.gms.**

# Kotlin coroutines internals.
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# Keep line numbers so a stored crash report (§16.6) is readable after export.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
