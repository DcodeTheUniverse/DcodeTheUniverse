# Anthropic SDK + Jackson use reflection for (de)serialization.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault
-keep class com.anthropic.core.** { *; }
-keep class com.anthropic.errors.** { *; }
-keep class com.anthropic.helpers.** { *; }
-keep class com.anthropic.client.** { *; }
-keep class com.anthropic.models.* { *; }
-keep class com.anthropic.models.beta.* { *; }
-keep class com.anthropic.models.beta.messages.** { *; }
-keep class com.anthropic.models.messages.** { *; }
-keep class com.anthropic.services.blocking.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.** { *; }
-keep class kotlin.jvm.internal.** { *; }
-keepclassmembers class * {
    @com.fasterxml.jackson.annotation.* <fields>;
    @com.fasterxml.jackson.annotation.* <init>(...);
    @com.fasterxml.jackson.annotation.* <methods>;
}
-dontwarn com.fasterxml.jackson.**
-dontwarn java.beans.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.slf4j.**
-dontwarn org.ietf.jgss.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn io.swagger.**
-dontwarn org.jetbrains.annotations.**
-dontwarn com.google.auto.value.**
-dontwarn java.lang.reflect.AnnotatedType
-dontwarn java.lang.reflect.AnnotatedParameterizedType
-dontwarn com.github.victools.**
-dontwarn io.opentelemetry.**

# LiteRT-LM (on-device models): native code calls back into these classes by name (JNI), and tool
# declarations are read reflectively. Keep the whole API.
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclassmembers class com.google.ai.edge.litertlm.** { native <methods>; }
-dontwarn com.google.ai.edge.litertlm.**
# LiteRT-LM passes messages to its native side as Gson JSON; keep Gson whole so nothing it needs is renamed.
-keep class com.google.gson.** { *; }
