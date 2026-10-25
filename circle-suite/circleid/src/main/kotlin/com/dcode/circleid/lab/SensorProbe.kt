@file:Suppress("DEPRECATION")

package com.dcode.circleid.lab

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.fingerprint.FingerprintManager
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import com.dcode.circleid.security.Biometrics
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.TimeUnit

enum class Verdict { Available, Blocked, Absent, Info }

data class ProbeResult(val group: String, val title: String, val verdict: Verdict, val detail: String)

/**
 * "Can we reach the ultrasonic sensor?" — every avenue an unprivileged app has, tried for real.
 *
 * Samsung's ultrasonic sensors (Qualcomm 3D Sonic) image the finger in 3D, including sub-surface ridge
 * depth, but the raw ultrasound frames are processed inside a Trusted Application in the TEE. Android only
 * hands apps a yes/no answer through BiometricPrompt. These probes document exactly what this device allows.
 */
object SensorProbe {
    fun run(context: Context): List<ProbeResult> = buildList {
        addAll(biometricClasses(context))
        addAll(features(context))
        addAll(ultrasonicHeuristics())
        addAll(hiddenSensorProperties(context))
        addAll(sensorList(context))
        addAll(deviceNodes())
        addAll(properties())
        addAll(vendorPackages(context))
    }

    private fun biometricClasses(context: Context): List<ProbeResult> {
        val bm = BiometricManager.from(context)
        fun r(name: String, a: Int): ProbeResult {
            val s = bm.canAuthenticate(a)
            return ProbeResult("Biometric API", name, if (s == BiometricManager.BIOMETRIC_SUCCESS) Verdict.Available else Verdict.Absent, Biometrics.describe(s))
        }
        return listOf(
            r("Class 3 (strong) biometric", BIOMETRIC_STRONG),
            r("Class 2 (weak) biometric", BIOMETRIC_WEAK),
            r("Device credential", DEVICE_CREDENTIAL),
        )
    }

    private fun features(context: Context): List<ProbeResult> {
        val pm = context.packageManager
        val items = listOf(
            PackageManager.FEATURE_FINGERPRINT to "Fingerprint hardware",
            PackageManager.FEATURE_FACE to "Face hardware",
            PackageManager.FEATURE_IRIS to "Iris hardware",
            PackageManager.FEATURE_STRONGBOX_KEYSTORE to "StrongBox secure element",
            PackageManager.FEATURE_HARDWARE_KEYSTORE to "Hardware Keystore",
            PackageManager.FEATURE_CAMERA_FLASH to "Rear torch (for pulse sensing)",
        )
        return items.map { (f, label) ->
            ProbeResult("System features", label, if (pm.hasSystemFeature(f)) Verdict.Available else Verdict.Absent, f)
        }
    }

    /** Samsung flagships with Qualcomm 3D Sonic ultrasonic sensors, by model number family. */
    private fun ultrasonicHeuristics(): List<ProbeResult> {
        val model = Build.MODEL.uppercase()
        val families = listOf(
            "SM-G97" to "Galaxy S10 family", "SM-N97" to "Galaxy Note10 family", "SM-G98" to "Galaxy S20 family",
            "SM-N98" to "Galaxy Note20 family", "SM-G99" to "Galaxy S21 family", "SM-S90" to "Galaxy S22 family",
            "SM-S91" to "Galaxy S23 family", "SM-S92" to "Galaxy S24 family", "SM-S93" to "Galaxy S25 family",
            "SM-S94" to "Galaxy S26 family", "SM-F9" to "Galaxy Z Fold (side sensor: capacitive)",
        )
        val hit = families.firstOrNull { model.startsWith(it.first) }
        val samsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE
        val verdict = when {
            hit != null && !hit.second.contains("capacitive") -> Verdict.Available
            samsung -> Verdict.Info
            else -> Verdict.Absent
        }
        return listOf(
            ProbeResult("Ultrasonic sensor", "Device", Verdict.Info, "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) · SoC $soc · Android ${Build.VERSION.RELEASE}"),
            ProbeResult(
                "Ultrasonic sensor",
                "Likely Qualcomm 3D Sonic",
                verdict,
                hit?.let { "${it.second}: in-display ultrasonic sensor expected (S10e/FE/A-series use optical or capacitive)" }
                    ?: "Model not in the known ultrasonic list",
            ),
        )
    }

    /**
     * The framework knows the sensor type (FingerprintSensorProperties.TYPE_UDFPS_ULTRASONIC) but only exposes it
     * through @SystemApi / @hide methods. We try them by reflection; on production builds hidden-API enforcement
     * normally blocks the call, which is itself the answer.
     */
    private fun hiddenSensorProperties(context: Context): List<ProbeResult> {
        val out = ArrayList<ProbeResult>()
        @Suppress("DEPRECATION")
        val fm = context.getSystemService(FingerprintManager::class.java)
        if (fm == null) {
            out.add(ProbeResult("Framework", "FingerprintManager", Verdict.Absent, "Service not available"))
            return out
        }
        out.add(
            ProbeResult(
                "Framework", "FingerprintManager.isHardwareDetected",
                Verdict.Info, runCatching { @Suppress("DEPRECATION") fm.isHardwareDetected.toString() }.getOrElse { it.javaClass.simpleName },
            ),
        )
        for (method in listOf("getSensorProperties", "getSensorPropertiesInternal")) {
            val res = try {
                val m = FingerprintManager::class.java.getMethod(method)
                val list = m.invoke(fm) as? List<*>
                val described = list.orEmpty().joinToString { p ->
                    val type = runCatching { p!!.javaClass.getMethod("getSensorType").invoke(p) }.getOrNull()
                        ?: runCatching { p!!.javaClass.getField("sensorType").get(p) }.getOrNull()
                    "type=${sensorTypeName(type as? Int)}"
                }
                ProbeResult("Framework", "$method()", Verdict.Available, described.ifEmpty { "Returned no sensors" })
            } catch (e: NoSuchMethodException) {
                ProbeResult("Framework", "$method()", Verdict.Blocked, "Hidden by the platform's non-SDK interface restrictions")
            } catch (e: Throwable) {
                ProbeResult("Framework", "$method()", Verdict.Blocked, "${(e.cause ?: e).javaClass.simpleName}: ${(e.cause ?: e).message ?: "denied"}")
            }
            out.add(res)
        }
        return out
    }

    private fun sensorTypeName(t: Int?): String = when (t) {
        0 -> "UNKNOWN"
        1 -> "REAR"
        2 -> "UDFPS_OPTICAL"
        3 -> "UDFPS_ULTRASONIC"
        4 -> "POWER_BUTTON"
        5 -> "HOME_BUTTON"
        null -> "?"
        else -> "type $t"
    }

    private fun sensorList(context: Context): List<ProbeResult> {
        val sm = context.getSystemService(SensorManager::class.java) ?: return emptyList()
        val all = sm.getSensorList(Sensor.TYPE_ALL)
        val keywords = listOf("finger", "sonic", "ultra", "fp", "bio", "touch", "hrm", "ppg", "heart")
        val interesting = all.filter { s -> keywords.any { k -> s.name.lowercase().contains(k) || s.stringType.lowercase().contains(k) } }
        val out = ArrayList<ProbeResult>()
        out.add(ProbeResult("Sensor HAL", "Sensors visible to apps", Verdict.Info, "${all.size} sensors"))
        if (interesting.isEmpty()) {
            out.add(ProbeResult("Sensor HAL", "Fingerprint/ultrasonic sensors in SensorManager", Verdict.Absent, "None — the fingerprint sensor is not a SensorManager sensor"))
        } else {
            interesting.forEach { s ->
                out.add(ProbeResult("Sensor HAL", s.name, Verdict.Info, "${s.vendor} · ${s.stringType} · type ${s.type}"))
            }
        }
        return out
    }

    /** Raw device nodes used by fingerprint drivers. SELinux denies untrusted apps; we record the exact error. */
    private fun deviceNodes(): List<ProbeResult> {
        val nodes = listOf(
            "/dev/qbt_handler" to "Qualcomm 3D Sonic handler", "/dev/qbt1000" to "Qualcomm QBT1000", "/dev/qbt2000" to "Qualcomm QBT2000",
            "/dev/qseecom" to "Qualcomm TEE comm", "/dev/esfp0" to "Egis", "/dev/goodix_fp" to "Goodix", "/dev/fpc1020" to "FPC",
            "/dev/silead_fp" to "Silead", "/dev/vfsspi" to "Synaptics",
        )
        return nodes.map { (path, label) ->
            val f = File(path)
            val (verdict, detail) = try {
                FileInputStream(f).use { it.read() }
                Verdict.Available to "Readable (unexpected — please report)"
            } catch (e: Exception) {
                val msg = e.message.orEmpty()
                when {
                    msg.contains("ENOENT") || msg.contains("No such file") -> Verdict.Absent to "Not present"
                    msg.contains("EACCES") || msg.contains("Permission denied") -> Verdict.Blocked to "Exists, blocked by SELinux"
                    else -> Verdict.Blocked to msg.ifEmpty { e.javaClass.simpleName }
                }
            }
            ProbeResult("Kernel device nodes", "$label  $path", verdict, detail)
        }
    }

    private fun properties(): List<ProbeResult> {
        val lines = try {
            val proc = ProcessBuilder("getprop").redirectErrorStream(true).start()
            val text = proc.inputStream.bufferedReader().readText()
            proc.waitFor(2, TimeUnit.SECONDS)
            text.lines()
        } catch (e: Exception) {
            return listOf(ProbeResult("System properties", "getprop", Verdict.Blocked, e.message ?: "denied"))
        }
        val keys = listOf("fingerprint", "fp.", ".fp", "sonic", "qbt", "qfp", "biometric", "udfps", "bauth")
        val matches = lines.filter { l -> keys.any { l.lowercase().contains(it) } && !l.contains("ro.build.fingerprint") && !l.contains("ro.bootimage") && !l.contains("build.fingerprint") }
        if (matches.isEmpty()) return listOf(ProbeResult("System properties", "Fingerprint-related properties", Verdict.Absent, "None readable by apps (${lines.size} properties visible)"))
        return matches.take(24).map { ProbeResult("System properties", it.substringBefore("]:").removePrefix("[").trim(), Verdict.Info, it.substringAfter("]: ").trim()) }
    }

    private fun vendorPackages(context: Context): List<ProbeResult> {
        val pkgs = listOf(
            "com.samsung.android.biometrics.app.setting" to "Samsung biometrics UI",
            "com.samsung.android.bio.face.service" to "Samsung face service",
            "com.samsung.android.samsungpass" to "Samsung Pass",
        )
        return pkgs.map { (p, label) ->
            val installed = runCatching { context.packageManager.getPackageInfo(p, 0); true }.getOrDefault(false)
            ProbeResult("Vendor stack", label, if (installed) Verdict.Info else Verdict.Absent, if (installed) "$p installed — no public API for raw sensor data" else "Not installed")
        }
    }

    const val CONCLUSION =
        "Raw ultrasonic frames (the data that would contain sub-surface ridge depth and, in principle, vascular " +
            "structure) are processed inside the Qualcomm Trusted Application in the TEE and are never exposed to " +
            "Android apps — not through BiometricPrompt, SensorManager, device nodes or vendor SDKs. Circle ID " +
            "therefore uses the ultrasonic sensor the only way that is possible and secure: as a Class 3 matcher " +
            "whose success cryptographically releases a hardware-bound key. For the 'vein' layer it uses the " +
            "camera instead: the torch transilluminates your fingertip, the camera measures the pulsing blood " +
            "volume (proof of living, perfused tissue) and an experimental texture signature of the light pattern."
}
