# kotlinx.serialization: keep generated serializers of our models (vault-core, protocol).
-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers @kotlinx.serialization.Serializable class com.dcode.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.dcode.**$$serializer { *; }

# Bouncy Castle: only Argon2 is used; the rest is shrunk away.
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
