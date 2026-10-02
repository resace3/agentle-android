# kotlinx.serialization: keep generated serializers of @Serializable classes.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class **$$serializer { *; }
-keepclassmembers @kotlinx.serialization.Serializable class ** { *** Companion; *** serializer(...); }
# Nimbus JOSE+JWT optional dependencies.
-dontwarn com.google.crypto.tink.subtle.**
-dontwarn org.bouncycastle.**
-dontwarn net.minidev.**
