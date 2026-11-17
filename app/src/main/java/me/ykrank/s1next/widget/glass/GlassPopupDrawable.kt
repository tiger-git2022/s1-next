package me.ykrank.s1next.widget.glass

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.PixelCopy
import android.view.View
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import org.xmlpull.v1.XmlPullParser

/**
 * Fork（tiger-git2022）：三个点弹出菜单的玻璃背景（res/drawable-v31/fork_glass_popup.xml）。
 *
 * 弹出菜单是独立的窗口，拿不到下面的 View 来实时模糊。菜单打开时下面的内容不会变，
 * 所以第一次绘制时用 PixelCopy 复制 Activity 窗口里菜单底下那块，模糊后作为背景，再叠一层半透明的中性色
 * （顶栏颜色偏浅时用白色，偏深时用黑色；用顶栏的颜色会和菜单底下的顶栏叠成一片发闷）。
 * 复制完成前先用较浓的颜色顶上，避免闪一下透明。
 */
@RequiresApi(Build.VERSION_CODES.S)
class GlassPopupDrawable : Drawable() {

    private var density = Resources.getSystem().displayMetrics.density
    private val node = RenderNode("glass-popup")
    private var snapshot: Bitmap? = null
    private var requested = false
    private var tintColor = 0xFFEEEEEE.toInt()
    private val clip = Path()
    private val location = IntArray(2)
    private val windowLocation = IntArray(2)
    private var watchedHost: View? = null

    // 同一个弹窗（比如下拉框）每次打开都复用这个背景：关闭时清掉截图，下次打开重新截
    private val detachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {}

        override fun onViewDetachedFromWindow(v: View) {
            requested = false
            snapshot = null
        }
    }

    override fun inflate(r: Resources, parser: XmlPullParser, attrs: AttributeSet, theme: Resources.Theme?) {
        super.inflate(r, parser, attrs, theme)
        density = r.displayMetrics.density
    }

    private val radius get() = 24 * density

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        requested = false
    }

    override fun draw(canvas: Canvas) {
        val host = callback as? View
        if (host != null && host !== watchedHost) {
            watchedHost?.removeOnAttachStateChangeListener(detachListener)
            host.addOnAttachStateChangeListener(detachListener)
            watchedHost = host
            requested = false
            snapshot = null
        }
        if (!requested && host != null && host.isAttachedToWindow && !bounds.isEmpty) {
            requested = true
            requestSnapshot(host)
        }
        val b = RectF(bounds)
        clip.reset()
        clip.addRoundRect(b, radius, radius, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(clip)
        val opacity = GlassSettings.opacity(dark = tintColor != Color.WHITE)
        val ready = snapshot != null && canvas.isHardwareAccelerated
        if (ready) {
            canvas.save()
            canvas.translate(b.left, b.top)
            canvas.drawRenderNode(node)
            canvas.restore()
        }
        // 复制完成前用较浓的颜色，避免一闪而过的透明
        val alpha = if (ready) opacity else maxOf(opacity, 92)
        canvas.drawColor(ColorUtils.setAlphaComponent(tintColor, alpha * 255 / 100))
        canvas.restoreToCount(save)
    }

    private fun requestSnapshot(host: View) {
        val activity = host.context.findActivity() ?: return
        GlassSettings.init(activity)
        GlassBars.tintColorOf(activity)?.let {
            tintColor = if (ColorUtils.calculateLuminance(it) > 0.5) Color.WHITE else GlassBars.DARK_GLASS_TINT
        }
        val w = bounds.width()
        val h = bounds.height()
        // 第一次绘制时弹窗还在播放入场动画（位移、缩放），所以不用 getLocationOnScreen，
        // 而是用弹窗窗口的位置加上各层 View 的布局位置，得到动画结束后的最终位置
        untransformedLocationOnScreen(host, location)
        activity.window.decorView.getLocationOnScreen(windowLocation)
        val left = location[0] - windowLocation[0] + bounds.left
        val top = location[1] - windowLocation[1] + bounds.top
        val decor = activity.window.decorView
        val src = Rect(left, top, left + w, top + h)
        if (!src.intersect(0, 0, decor.width, decor.height) || src.isEmpty) return
        val bitmap = Bitmap.createBitmap(src.width(), src.height(), Bitmap.Config.ARGB_8888)
        PixelCopy.request(activity.window, src, bitmap, { result ->
            if (result != PixelCopy.SUCCESS) return@request
            snapshot = bitmap
            node.setPosition(0, 0, w, h)
            val px = GlassSettings.blurDp * density
            node.setRenderEffect(
                // 模糊后再提一点饱和度（1.4 倍），透出来的颜色更鲜，接近系统桌面组件的玻璃
                if (px > 0f) RenderEffect.createChainEffect(
                    RenderEffect.createColorFilterEffect(
                        android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(1.4f) })
                    ),
                    RenderEffect.createBlurEffect(px, px, Shader.TileMode.CLAMP)
                ) else null
            )
            val c = node.beginRecording(w, h)
            try {
                c.drawBitmap(bitmap, (src.left - left).toFloat(), (src.top - top).toFloat(), null)
            } finally {
                node.endRecording()
            }
            invalidateSelf()
        }, Handler(Looper.getMainLooper()))
    }

    private fun untransformedLocationOnScreen(view: View, out: IntArray) {
        var x = 0
        var y = 0
        var v = view
        while (true) {
            val parent = v.parent as? View ?: break
            x += v.left - parent.scrollX
            y += v.top - parent.scrollY
            v = parent
        }
        // 根 View 自己的位移（动画）也扣掉
        v.getLocationOnScreen(out)
        out[0] += x - v.translationX.toInt()
        out[1] += y - v.translationY.toInt()
    }

    override fun getPadding(padding: Rect): Boolean {
        val v = (8 * density).toInt()
        padding.set(0, v, 0, v)
        return true
    }

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, radius)
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
