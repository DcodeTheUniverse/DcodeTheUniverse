# LiteRT / TensorFlow Lite uses JNI callbacks into these classes.
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**
-keep class com.google.ai.edge.litert.** { *; }
-dontwarn com.google.ai.edge.litert.**

# kotlinx.serialization: keep generated serializers of our models.
-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers @kotlinx.serialization.Serializable class com.dcode.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.dcode.**$$serializer { *; }

# Hidden-API probes in Sensor Lab are looked up by name via reflection on framework classes only.
-dontwarn org.bouncycastle.**
