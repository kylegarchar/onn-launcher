package dev.lumen.launcher

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.DisplayMetrics
import android.util.TypedValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Turns an app into a sharp 16:9 card.
 *
 * Order of preference:
 *  1. A PNG the user pushed over adb (see README, "Custom icons").
 *  2. The disk cache.
 *  3. The app's own TV banner, read at its highest shipped resolution and scaled down once.
 *  4. A generated card built from the app's adaptive or legacy icon.
 *
 * Runs on a background thread. Nothing here may throw into the caller.
 */
object IconLoader {
    private const val CACHE_VERSION = 1
    private const val NEUTRAL = 0xFF2A2B31.toInt()
    private const val LIGHT = 0xFFD9DAE0.toInt()

    class Result(val card: ImageBitmap, val ambient: ImageBitmap)

    fun load(context: Context, entry: AppEntry, w: Int, h: Int): Result? {
        var bitmap = loadOverride(context, entry, w, h)
        if (bitmap == null) bitmap = readCache(context, entry, w, h)
        if (bitmap == null) {
            bitmap = render(context, entry, w, h) ?: return null
            writeCache(context, entry, w, h, bitmap)
        }
        val ambient = makeAmbient(bitmap)
        bitmap.prepareToDraw()
        return Result(bitmap.asImageBitmap(), ambient.asImageBitmap())
    }

    /** Folder the user can `adb push` replacement artwork into. Created on first use. */
    fun overrideDir(context: Context): File? = try {
        context.getExternalFilesDir("icons")
    } catch (e: Throwable) {
        null
    }

    // ---------------------------------------------------------------- sources

    private fun loadOverride(context: Context, entry: AppEntry, w: Int, h: Int): Bitmap? {
        val dir = overrideDir(context) ?: return null
        for (ext in arrayOf("png", "webp", "jpg")) {
            val file = File(dir, entry.packageName + "." + ext)
            if (!file.isFile) continue
            try {
                val bounds = BitmapFactory.Options()
                bounds.inJustDecodeBounds = true
                BitmapFactory.decodeFile(file.path, bounds)
                val options = decodeOptions()
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= w * 2 && bounds.outHeight / (sample * 2) >= h * 2) {
                    sample *= 2
                }
                options.inSampleSize = sample
                val source = BitmapFactory.decodeFile(file.path, options) ?: continue
                return ensureOpaque(cover(source, w, h))
            } catch (e: Throwable) {
                continue
            }
        }
        return null
    }

    private fun cacheFile(context: Context, entry: AppEntry, w: Int, h: Int): File {
        val safeId = entry.id.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val name = safeId + "@" + entry.stamp + "@" + w + "x" + h + "@v" + CACHE_VERSION + ".png"
        return File(File(context.cacheDir, "icons"), name)
    }

    private fun readCache(context: Context, entry: AppEntry, w: Int, h: Int): Bitmap? = try {
        val file = cacheFile(context, entry, w, h)
        if (file.isFile) BitmapFactory.decodeFile(file.path, decodeOptions()) else null
    } catch (e: Throwable) {
        null
    }

    private fun writeCache(context: Context, entry: AppEntry, w: Int, h: Int, bitmap: Bitmap) {
        try {
            val file = cacheFile(context, entry, w, h)
            val dir = file.parentFile ?: return
            dir.mkdirs()
            // Drop cards cached for older versions of the same app.
            val prefix = file.name.substringBefore('@') + "@"
            dir.listFiles()?.forEach { old ->
                if (old.name.startsWith(prefix) && old.name != file.name) old.delete()
            }
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } catch (e: Throwable) {
            // A missing cache only costs a re-render next launch.
        }
    }

    @Suppress("DEPRECATION")
    private fun render(context: Context, entry: AppEntry, w: Int, h: Int): Bitmap? {
        if (entry.isBuiltin) return settingsCard(w, h)
        val component = entry.component ?: return null
        val pm = context.packageManager
        val activity = try {
            pm.getActivityInfo(component, 0)
        } catch (e: Throwable) {
            return letterCard(entry.label, w, h)
        }
        val appInfo = activity.applicationInfo
        val res: Resources? = try {
            pm.getResourcesForApplication(appInfo)
        } catch (e: Throwable) {
            null
        }

        val bannerId = if (activity.banner != 0) activity.banner else appInfo.banner
        if (res != null && bannerId != 0) {
            val raw = rawBitmap(res, bannerId)
            if (raw != null) {
                try {
                    return ensureOpaque(cover(raw, w, h))
                } catch (e: Throwable) {
                    // Fall through to the drawable path.
                }
            }
            val drawable = drawableFor(res, bannerId)
            if (drawable != null) {
                val drawn = drawCover(drawable, w, h)
                if (drawn != null) return ensureOpaque(drawn)
            }
        }

        val iconId = if (activity.icon != 0) activity.icon else appInfo.icon
        var icon: Drawable? = if (res != null && iconId != 0) drawableFor(res, iconId) else null
        if (icon == null) {
            icon = try {
                activity.loadIcon(pm)
            } catch (e: Throwable) {
                null
            }
        }
        if (icon == null) return letterCard(entry.label, w, h)
        return try {
            iconCard(icon, w, h)
        } catch (e: Throwable) {
            letterCard(entry.label, w, h)
        }
    }

    /**
     * Opens the banner file straight from the APK, picking the highest-density variant the
     * app ships and decoding it without any density scaling. Going through the normal
     * drawable loader instead would resample the image twice and soften it.
     */
    private fun rawBitmap(res: Resources, id: Int): Bitmap? = try {
        val value = TypedValue()
        res.getValueForDensity(id, DisplayMetrics.DENSITY_XXXHIGH, value, true)
        val path = value.string?.toString()
        if (path == null || !isRaster(path)) {
            null
        } else {
            res.assets.openNonAssetFd(value.assetCookie, path).use { fd ->
                fd.createInputStream().use { stream ->
                    BitmapFactory.decodeStream(stream, null, decodeOptions())
                }
            }
        }
    } catch (e: Throwable) {
        null
    }

    private fun isRaster(path: String): Boolean {
        val p = path.lowercase()
        if (p.endsWith(".9.png")) return false
        return p.endsWith(".png") || p.endsWith(".webp") || p.endsWith(".jpg") || p.endsWith(".jpeg")
    }

    private fun drawableFor(res: Resources, id: Int): Drawable? = try {
        res.getDrawableForDensity(id, DisplayMetrics.DENSITY_XXXHIGH, null)
    } catch (e: Throwable) {
        null
    }

    private fun decodeOptions(): BitmapFactory.Options {
        val options = BitmapFactory.Options()
        options.inScaled = false
        options.inPreferredConfig = Bitmap.Config.ARGB_8888
        return options
    }

    // ---------------------------------------------------------------- scaling

    private fun soft(bitmap: Bitmap): Bitmap {
        if (bitmap.config != Bitmap.Config.HARDWARE) return bitmap
        return bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap
    }

    /**
     * Centre-crops [source] to the target aspect ratio and scales it to exactly [w] x [h].
     * Large images are halved step by step first, which avoids the shimmer and jagged
     * edges a single big bilinear reduction produces.
     */
    private fun cover(source: Bitmap, w: Int, h: Int): Bitmap {
        val src = soft(source)
        val srcAspect = src.width.toFloat() / src.height.toFloat()
        val dstAspect = w.toFloat() / h.toFloat()
        var cropW = src.width
        var cropH = src.height
        if (srcAspect > dstAspect) {
            cropW = (src.height * dstAspect).roundToInt().coerceIn(1, src.width)
        } else {
            cropH = (src.width / dstAspect).roundToInt().coerceIn(1, src.height)
        }
        var current = if (cropW != src.width || cropH != src.height) {
            Bitmap.createBitmap(src, (src.width - cropW) / 2, (src.height - cropH) / 2, cropW, cropH)
        } else {
            src
        }
        while (current.width >= w * 2 && current.height >= h * 2) {
            current = Bitmap.createScaledBitmap(current, current.width / 2, current.height / 2, true)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        Canvas(out).drawBitmap(current, null, Rect(0, 0, w, h), paint)
        return out
    }

    private fun drawCover(drawable: Drawable, w: Int, h: Int): Bitmap? = try {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            cover(drawable.bitmap, w, h)
        } else {
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val iw = drawable.intrinsicWidth
            val ih = drawable.intrinsicHeight
            if (iw > 0 && ih > 0) {
                val scale = max(w / iw.toFloat(), h / ih.toFloat())
                val dw = (iw * scale).roundToInt()
                val dh = (ih * scale).roundToInt()
                val left = (w - dw) / 2
                val top = (h - dh) / 2
                drawable.setBounds(left, top, left + dw, top + dh)
            } else {
                drawable.setBounds(0, 0, w, h)
            }
            drawable.draw(canvas)
            out
        }
    } catch (e: Throwable) {
        null
    }

    private fun drawInto(canvas: Canvas, drawable: Drawable, dst: Rect) {
        try {
            if (drawable is BitmapDrawable && drawable.bitmap != null) {
                val scaled = cover(drawable.bitmap, dst.width(), dst.height())
                canvas.drawBitmap(scaled, dst.left.toFloat(), dst.top.toFloat(), null)
            } else {
                drawable.setBounds(dst.left, dst.top, dst.right, dst.bottom)
                drawable.draw(canvas)
            }
        } catch (e: Throwable) {
            // Leave whatever is already painted underneath.
        }
    }

    /** Banners with see-through areas get a solid backing so the wallpaper never shows through. */
    private fun ensureOpaque(source: Bitmap): Bitmap {
        if (!source.hasAlpha()) return source
        val w = source.width
        val h = source.height
        val stepX = max(1, w / 24)
        val stepY = max(1, h / 14)
        var clear = 0
        var total = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                total++
                if ((source.getPixel(x, y) ushr 24) < 245) clear++
                x += stepX
            }
            y += stepY
        }
        if (clear * 50 < total) return source
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        paintGradient(canvas, w, h, NEUTRAL)
        canvas.drawBitmap(source, 0f, 0f, null)
        return out
    }

    // ---------------------------------------------------------------- generated cards

    private fun iconCard(icon: Drawable, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val foreground = if (icon is AdaptiveIconDrawable) icon.foreground else null

        if (icon is AdaptiveIconDrawable && foreground != null) {
            // The background layer fills the whole card; the logo layer sits large on top.
            paintGradient(canvas, w, h, NEUTRAL)
            val background = icon.background
            if (background != null) {
                val top = (h - w) / 2
                drawInto(canvas, background, Rect(0, top, w, top + w))
            }
            val size = (h * 1.5f).roundToInt()
            val left = (w - size) / 2
            val top = (h - size) / 2
            drawInto(canvas, foreground, Rect(left, top, left + size, top + size))
        } else {
            val probe = drawCover(icon, 48, 48)
            val tone = if (probe != null) analyze(probe) else Tone(NEUTRAL, 0.5f)
            val base = if (tone.luma < 0.2f) LIGHT else deepen(tone.color)
            paintGradient(canvas, w, h, base)
            val size = (h * 0.6f).roundToInt()
            val left = (w - size) / 2
            val top = (h - size) / 2
            drawInto(canvas, icon, Rect(left, top, left + size, top + size))
        }
        return out
    }

    private fun settingsCard(w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        paintGradient(canvas, w, h, 0xFF50525A.toInt())

        val cx = w / 2f
        val cy = h / 2f
        val r = h * 0.25f
        val gear = Path()
        gear.addCircle(cx, cy, r, Path.Direction.CW)
        val toothW = r * 0.44f
        val toothH = r * 0.40f
        val matrix = Matrix()
        for (i in 0 until 8) {
            val tooth = Path()
            tooth.addRoundRect(
                RectF(cx - toothW / 2f, cy - r - toothH * 0.75f, cx + toothW / 2f, cy - r + toothH * 0.5f),
                toothW * 0.2f,
                toothW * 0.2f,
                Path.Direction.CW,
            )
            matrix.reset()
            matrix.setRotate(i * 45f, cx, cy)
            tooth.transform(matrix)
            gear.op(tooth, Path.Op.UNION)
        }
        val hole = Path()
        hole.addCircle(cx, cy, r * 0.42f, Path.Direction.CW)
        gear.op(hole, Path.Op.DIFFERENCE)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = 0xF2FFFFFF.toInt()
        canvas.drawPath(gear, paint)
        return out
    }

    private fun letterCard(label: String, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        paintGradient(canvas, w, h, NEUTRAL)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = 0xE6FFFFFF.toInt()
        paint.textSize = h * 0.42f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textAlign = Paint.Align.CENTER
        val letter = label.trim().take(1).uppercase()
        val fm = paint.fontMetrics
        canvas.drawText(letter, w / 2f, h / 2f - (fm.ascent + fm.descent) / 2f, paint)
        return out
    }

    private fun paintGradient(canvas: Canvas, w: Int, h: Int, base: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        paint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            shade(base, 1.16f), shade(base, 0.80f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    }

    // ---------------------------------------------------------------- colour

    private class Tone(val color: Int, val luma: Float)

    /** Dominant colour of an icon, weighted towards its saturated, bright pixels. */
    private fun analyze(bitmap: Bitmap): Tone {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val hsv = FloatArray(3)
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var weightSum = 0.0
        var lumaSum = 0.0
        var count = 0
        for (c in pixels) {
            if ((c ushr 24) < 128) continue
            Color.colorToHSV(c, hsv)
            val weight = 0.15 + hsv[1] * hsv[2]
            r += Color.red(c) * weight
            g += Color.green(c) * weight
            b += Color.blue(c) * weight
            weightSum += weight
            lumaSum += (0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)) / 255.0
            count++
        }
        if (count == 0 || weightSum <= 0.0) return Tone(NEUTRAL, 0.5f)
        val color = Color.rgb(
            (r / weightSum).roundToInt().coerceIn(0, 255),
            (g / weightSum).roundToInt().coerceIn(0, 255),
            (b / weightSum).roundToInt().coerceIn(0, 255),
        )
        return Tone(color, (lumaSum / count).toFloat())
    }

    /** A rich, dark version of [color] for the icon to sit on. */
    private fun deepen(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] = min(hsv[1], 0.80f)
        hsv[2] = (hsv[2] * 0.42f).coerceIn(0.17f, 0.34f)
        return Color.HSVToColor(hsv)
    }

    private fun shade(color: Int, factor: Float): Int = Color.rgb(
        (Color.red(color) * factor).roundToInt().coerceIn(0, 255),
        (Color.green(color) * factor).roundToInt().coerceIn(0, 255),
        (Color.blue(color) * factor).roundToInt().coerceIn(0, 255),
    )

    // ---------------------------------------------------------------- ambient backdrop

    /**
     * A tiny, heavily blurred copy of the card. Stretched across the screen it becomes the
     * soft colour wash behind the home screen, at none of the cost of a live blur.
     */
    private fun makeAmbient(card: Bitmap): Bitmap {
        val aw = 48
        val ah = 27
        var current = card
        while (current.width >= aw * 4 && current.height >= ah * 4) {
            current = Bitmap.createScaledBitmap(current, current.width / 2, current.height / 2, true)
        }
        val small = Bitmap.createScaledBitmap(current, aw, ah, true)
        val pixels = IntArray(aw * ah)
        small.getPixels(pixels, 0, aw, 0, 0, aw, ah)
        val scratch = IntArray(aw * ah)
        repeat(3) { boxBlur(pixels, scratch, aw, ah, 5) }

        val hsv = FloatArray(3)
        for (i in pixels.indices) {
            Color.colorToHSV(pixels[i], hsv)
            hsv[1] = min(1f, hsv[1] * 1.25f)
            hsv[2] = min(0.92f, hsv[2] * 0.95f + 0.04f)
            pixels[i] = Color.HSVToColor(hsv)
        }
        val out = Bitmap.createBitmap(aw, ah, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, aw, 0, 0, aw, ah)
        return out
    }

    private fun boxBlur(pixels: IntArray, scratch: IntArray, w: Int, h: Int, radius: Int) {
        // Horizontal pass: pixels -> scratch.
        for (y in 0 until h) {
            for (x in 0 until w) {
                var r = 0
                var g = 0
                var b = 0
                var n = 0
                for (k in -radius..radius) {
                    val c = pixels[y * w + (x + k).coerceIn(0, w - 1)]
                    r += (c shr 16) and 0xFF
                    g += (c shr 8) and 0xFF
                    b += c and 0xFF
                    n++
                }
                scratch[y * w + x] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
        // Vertical pass: scratch -> pixels.
        for (y in 0 until h) {
            for (x in 0 until w) {
                var r = 0
                var g = 0
                var b = 0
                var n = 0
                for (k in -radius..radius) {
                    val c = scratch[(y + k).coerceIn(0, h - 1) * w + x]
                    r += (c shr 16) and 0xFF
                    g += (c shr 8) and 0xFF
                    b += c and 0xFF
                    n++
                }
                pixels[y * w + x] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
    }
}
