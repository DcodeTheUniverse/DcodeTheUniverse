// Runs the app's own on-device runtime (LiteRtRuntime and the request types) against a real .litertlm model,
// using LiteRT-LM's desktop build. Not part of the app build or CI: it needs a 2.6–3.7 GB model file.
plugins {
    kotlin("jvm") version "2.4.21"
    application
}
dependencies {
    implementation("com.google.ai.edge.litertlm:litertlm-jvm:0.18.1")
    implementation("org.json:json:20240303")
}
kotlin { jvmToolchain(21) }
sourceSets["main"].kotlin {
    srcDir("../../app/src/main/java")
    include(
        "RuntimeCheck.kt",
        "com/axiom/launcher/solis/ondevice/LiteRtRuntime.kt",
        "com/axiom/launcher/solis/ondevice/LocalProtocol.kt",
        "com/axiom/launcher/solis/ondevice/GemmaTemplate.kt",
        "com/axiom/launcher/solis/llm/LlmTypes.kt",
        "com/axiom/launcher/solis/catalog/ModelCatalog.kt",
    )
}
application { mainClass.set("RuntimeCheckKt") }
tasks.named<JavaExec>("run") { jvmArgs("-Xmx2g") }
