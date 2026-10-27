package com.axiom.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.DisplayMetrics
import android.util.Xml
import androidx.core.graphics.createBitmap
import org.xmlpull.v1.XmlPullParser

/** An installed icon pack app. */
data class IconPackInfo(val pkg: String, val label: String)

/**
 * Icon packs in the common ADW / Nova format: the pack's appfilter.xml maps activities to drawables, and
 * may give a back plate, mask and overlay for apps it doesn't cover.
 */
object IconPacks {
    private val ACTIONS = listOf(
        "org.adw.launcher.THEMES", "com.novalauncher.THEME", "com.gau.go.launcherex.theme",
        "com.anddoes.launcher.THEME", "com.teslacoilsw.launcher.THEME", "com.fede.launcher.THEME_ICONPACK",
    )

    fun installed(context: Context): List<IconPackInfo> {
        val pm = context.packageManager
        return ACTIONS.flatMap { a -> runCatching { pm.queryIntentActivities(Intent(a), 0) }.getOrDefault(emptyList()) }
            .map { it.activityInfo.packageName }
            .distinct()
            .map { pkg -> IconPackInfo(pkg, runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)) }
            .sortedBy { it.label.lowercase() }
    }

    /** Reads the pack's appfilter; null if the pack is gone or has none. Takes up to a few hundred ms for big packs. */
    fun load(context: Context, pkg: String): IconPack? = runCatching {
        val res = context.packageManager.getResourcesForApplication(pkg)
        val id = res.getIdentifier("appfilter", "xml", pkg)
        val parser: XmlPullParser = if (id != 0) res.getXml(id) else Xml.newPullParser().apply {
            setInput(res.assets.open("appfilter.xml").use { it.reader().readText() }.reader())
        }
        parse(pkg, res, parser)
    }.getOrNull()

    internal fun parse(pkg: String, res: Resources?, parser: XmlPullParser): IconPack {
        val byComponent = HashMap<String, String>()
        val byPackage = HashMap<String, String>()
        val backs = ArrayList<String>()
        var mask: String? = null
        var upon: String? = null
        var scale = 1f
        while (true) {
            val ev = parser.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "item" -> {
                    val drawable = parser.getAttributeValue(null, "drawable")?.takeIf { it.isNotBlank() } ?: continue
                    val cn = componentOf(parser.getAttributeValue(null, "component")) ?: continue
                    byComponent.putIfAbsent(cn.flattenToString(), drawable)
                    byPackage.putIfAbsent(cn.packageName, drawable)
                }
                "iconback" -> for (i in 0 until parser.attributeCount) {
                    if (parser.getAttributeName(i).startsWith("img")) parser.getAttributeValue(i)?.takeIf { it.isNotBlank() }?.let { backs += it }
                }
                "iconmask" -> mask = parser.getAttributeValue(null, "img1") ?: mask
                "iconupon" -> upon = parser.getAttributeValue(null, "img1") ?: upon
                "scale" -> scale = parser.getAttributeValue(null, "factor")?.toFloatOrNull()?.coerceIn(0.2f, 1.5f) ?: scale
            }
        }
        return IconPack(pkg, res, byComponent, byPackage, backs, mask, upon, scale)
    }

    /** "ComponentInfo{com.app/com.app.Main}" or "com.app/.Main" → ComponentName. */
    internal fun componentOf(raw: String?): ComponentName? {
        val s = raw?.trim()?.removePrefix("ComponentInfo{")?.removeSuffix("}") ?: return null
        val slash = s.indexOf('/')
        if (slash <= 0 || slash == s.lastIndex) return null
        val pkg = s.substring(0, slash)
        val cls = s.substring(slash + 1).let { if (it.startsWith(".")) pkg + it else it }
        return ComponentName(pkg, cls)
    }
}

class IconPack internal constructor(
    val pkg: String,
    private val res: Resources?,
    private val byComponent: Map<String, String>,
    private val byPackage: Map<String, String>,
    private val backs: List<String>,
    private val mask: String?,
    private val upon: String?,
    private val scale: Float,
) {
    val size: Int get() = byComponent.size

    /** The drawable name the pack has for [cn]: exact activity first, then any activity of the same app. */
    fun drawableFor(cn: ComponentName): String? = byComponent[cn.flattenToString()] ?: byPackage[cn.packageName]

    /**
     * The pack's icon for [cn] at [sizePx]; for apps the pack doesn't cover, [original] composed onto the pack's
     * back plate (when it has one). Null means use the system icon.
     */
    fun icon(cn: ComponentName, sizePx: Int, original: () -> Drawable?): Bitmap? {
        drawableFor(cn)?.let { name -> drawable(name)?.let { return render(it, sizePx) } }
        if (backs.isEmpty() && mask == null && upon == null) return null
        val base = original() ?: return null
        val out = createBitmap(sizePx, sizePx)
        val c = Canvas(out)
        backs.getOrNull(Math.floorMod(cn.hashCode(), backs.size.coerceAtLeast(1)))?.let { drawable(it) }?.let { draw(c, it, sizePx, 1f) }
        val inner = createBitmap(sizePx, sizePx)
        draw(Canvas(inner), base, sizePx, scale)
        mask?.let { drawable(it) }?.let { m ->
            val ic = Canvas(inner)
            val maskBmp = render(m, sizePx)
            ic.drawBitmap(maskBmp, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
        }
        c.drawBitmap(inner, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        upon?.let { drawable(it) }?.let { draw(c, it, sizePx, 1f) }
        return out
    }

    private fun drawable(name: String): Drawable? {
        val r = res ?: return null
        val id = r.getIdentifier(name, "drawable", pkg).takeIf { it != 0 } ?: r.getIdentifier(name, "mipmap", pkg).takeIf { it != 0 } ?: return null
        return runCatching { r.getDrawableForDensity(id, DisplayMetrics.DENSITY_XXXHIGH, null) }.getOrNull()
    }

    private fun render(d: Drawable, sizePx: Int): Bitmap = createBitmap(sizePx, sizePx).also { draw(Canvas(it), d, sizePx, 1f) }

    private fun draw(c: Canvas, d: Drawable, sizePx: Int, factor: Float) {
        val inset = ((sizePx - sizePx * factor) / 2f).toInt()
        val old = Rect(d.bounds)
        d.setBounds(inset, inset, sizePx - inset, sizePx - inset)
        d.draw(c)
        d.bounds = old
    }
}
