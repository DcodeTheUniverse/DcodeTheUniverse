// Shared release-signing setup. Provide a keystore via environment variables (CI) or keystore.properties
// (local, git-ignored). Without one, release builds fall back to the debug key so they always install.
import java.util.Properties

val signingProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingValue(key: String, env: String): String? = signingProps.getProperty(key) ?: System.getenv(env)

extra["releaseStoreFile"] = signingValue("storeFile", "CIRCLE_SUITE_KEYSTORE")
extra["releaseStorePassword"] = signingValue("storePassword", "CIRCLE_SUITE_KEYSTORE_PASSWORD")
extra["releaseKeyAlias"] = signingValue("keyAlias", "CIRCLE_SUITE_KEY_ALIAS")
extra["releaseKeyPassword"] = signingValue("keyPassword", "CIRCLE_SUITE_KEY_PASSWORD")
