package me.ykrank.s1next.widget.glass

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.ColorInt
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils

/**
 * Fork（tiger-git2022）：玻璃顶栏背景。
 *
 * 每次绘制前（OnPreDraw）把顶栏下面的内容 [source] 录进一个 [RenderNode]，用 [RenderEffect] 模糊，
 * 再叠一层半透明的顶栏颜色。顶栏自身的显示列表只引用这个 RenderNode，
 * 所以内容滚动时不需要重绘顶栏，也不会因为「重绘 → OnPreDraw → 重绘」而一直刷新。
 */
@RequiresApi(Build.VERSION_CODES.S)
class GlassBarDrawable(
    private val host: View,
    private val source: View,
    @ColorInt private val tintColor: Int,
    @ColorInt private val backdropColor: Int,
    /** true：圆形（悬浮按钮）；false：矩形顶栏（不画边线） */
    private val oval: Boolean = false,
) : Drawable() {

    private val node = RenderNode("glass-bar")
    private val density = host.resources.displayMetrics.density

    // 模糊度和不透明度来自设置（GlassSettings），变化时在下一次绘制前更新
    private var appliedBlurDp = -1
    private var appliedOpacity = -1
    private var tint = 0
    /** 深色玻璃上画一圈浅色亮边；浅色玻璃不画（白天模式下会像一圈黑框） */
    private val ringPaint = if (ColorUtils.calculateLuminance(tintColor) > 0.5) null
    else Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x55FFFFFF
        strokeWidth = host.resources.displayMetrics.density
    }
    private val clip = Path()
    private val hostLocation = IntArray(2)
    private val sourceLocation = IntArray(2)

    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        record()
        true
    }
    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            v.viewTreeObserver.addOnPreDrawListener(preDrawListener)
        }

        override fun onViewDetachedFromWindow(v: View) {
            v.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
        }
    }

    init {
        GlassSettings.init(host.context)
        applySettings()
        host.addOnAttachStateChangeListener(attachListener)
        if (host.isAttachedToWindow) {
            host.viewTreeObserver.addOnPreDrawListener(preDrawListener)
        }
    }

    /** @return 设置是否有变化 */
    private fun applySettings(): Boolean {
        val blurDp = GlassSettings.blurDp
        val opacity = GlassSettings.opacityPercent
        if (blurDp == appliedBlurDp && opacity == appliedOpacity) return false
        if (blurDp != appliedBlurDp) {
            val px = blurDp * density
            node.setRenderEffect(
                if (px > 0f) RenderEffect.createBlurEffect(px, px, Shader.TileMode.CLAMP) else null
            )
            appliedBlurDp = blurDp
        }
        appliedOpacity = opacity
        tint = ColorUtils.setAlphaComponent(tintColor, opacity * 255 / 100)
        return true
    }

    private fun record() {
        if (applySettings()) invalidateSelf()
        val w = host.width
        val h = host.height
        if (w <= 0 || h <= 0 || !source.isLaidOut) return
        host.getLocationInWindow(hostLocation)
        source.getLocationInWindow(sourceLocation)
        node.setPosition(0, 0, w, h)
        val canvas = node.beginRecording(w, h)
        try {
            canvas.drawColor(backdropColor)
            canvas.translate(
                (sourceLocation[0] - hostLocation[0]).toFloat(),
                (sourceLocation[1] - hostLocation[1]).toFloat()
            )
            source.draw(canvas)
        } finally {
            node.endRecording()
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val save = canvas.save()
        if (oval) {
            clip.reset()
            clip.addOval(RectF(b), Path.Direction.CW)
            canvas.clipPath(clip)
        }
        if (canvas.isHardwareAccelerated) {
            if (!node.hasDisplayList()) record()
            canvas.drawRenderNode(node)
            canvas.drawColor(tint)
        } else {
            canvas.drawColor(tintColor)
        }
        canvas.restoreToCount(save)
        if (oval) {
            ringPaint?.let {
                val inset = it.strokeWidth / 2
                canvas.drawOval(RectF(b).apply { inset(inset, inset) }, it)
            }
        }
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
