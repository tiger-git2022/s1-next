package me.ykrank.s1next.widget.glass

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.annotation.ColorInt
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import me.ykrank.s1next.R

/**
 * Fork（tiger-git2022）：玻璃顶栏背景。
 *
 * 每次绘制前（OnPreDraw）把宿主下面的内容 [source] 录进一个 [RenderNode]，用 [RenderEffect] 模糊，
 * 再叠一层半透明的染色。录制范围向四周多留一圈 [edgePad]，RenderNode 保持从 (0, 0) 起步，
 * 绘制时再往回平移，避免负坐标 RenderNode 叠加 RenderEffect 模糊出现裁切线或错位。
 */
@RequiresApi(Build.VERSION_CODES.S)
class GlassBarDrawable(
    private val host: View,
    private val source: View,
    @ColorInt private val tintColor: Int,
    @ColorInt private val backdropColor: Int,
    /** true：圆形（悬浮按钮、下拉刷新圈）；false：矩形顶栏（不画边线） */
    private val oval: Boolean = false,
    /** 固定的不透明度（0..100），不跟设置走（楼层气泡） */
    private val fixedOpacity: Int? = null,
    /** 状态栏玻璃条：用设置里单独的状态栏不透明度 */
    private val statusBar: Boolean = false,
    /** 四个角的圆角半径（px）；Float.MAX_VALUE 为胶囊形（楼层气泡） */
    private val cornerRadius: Float = 0f,
    /** 只圆下面两个角（顶栏，上面接状态栏） */
    private val bottomCornerRadius: Float = 0f,
) : Drawable() {

    private val node = RenderNode("glass-bar")
    private val density = host.resources.displayMetrics.density

    // 模糊度和不透明度来自设置（GlassSettings），变化时在下一次绘制前更新
    private var appliedBlurDp = -1
    private var appliedOpacity = -1
    private var recordedPad = 0
    private var tint = 0
    /** 夜间（玻璃颜色是深色），用夜间的不透明度 */
    private val dark = ColorUtils.calculateLuminance(tintColor) < 0.5
    private val clip = Path()
    private val hostLocation = IntArray(2)
    private val sourceLocation = IntArray(2)
    private val visible = RectF()

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
        val opacity = fixedOpacity
            ?: if (statusBar) GlassSettings.statusOpacity(dark) else GlassSettings.opacity(dark)
        if (blurDp == appliedBlurDp && opacity == appliedOpacity) return false
        if (blurDp != appliedBlurDp) {
            val px = blurDp * density
            node.setRenderEffect(
                // 模糊后再提一点饱和度（1.4 倍），透出来的颜色更鲜，接近系统桌面组件的玻璃
                if (px > 0f) RenderEffect.createChainEffect(
                    RenderEffect.createColorFilterEffect(
                        android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(1.4f) })
                    ),
                    RenderEffect.createBlurEffect(px, px, Shader.TileMode.CLAMP)
                ) else null
            )
            appliedBlurDp = blurDp
        }
        appliedOpacity = opacity
        tint = ColorUtils.setAlphaComponent(tintColor, opacity * 255 / 100)
        return true
    }

    private fun record() {
        val settingsChanged = applySettings()
        if (settingsChanged) invalidateSelf()
        val w = host.width
        val h = host.height
        if (w <= 0 || h <= 0 || !source.isLaidOut) return
        if (!host.isShown || host.alpha <= 0f) return
        host.getLocationInWindow(hostLocation)
        source.getLocationInWindow(sourceLocation)
        // 四周多录一圈（约 2 倍模糊半径），RenderNode 从 (0, 0) 起步，绘制时往回挪一圈再裁回 host：
        // 避免负坐标 RenderNode + RenderEffect 模糊及嵌套子 RenderNode 导致的锐利裁切线与错位
        val pad = edgePad()
        if (pad != recordedPad) {
            recordedPad = pad
            invalidateSelf()
        }
        node.setPosition(0, 0, w + 2 * pad, h + 2 * pad)
        val canvas = node.beginRecording(w + 2 * pad, h + 2 * pad)
        try {
            canvas.drawColor(backdropColor)
            val dx = (sourceLocation[0] - hostLocation[0]).toFloat()
            val dy = (sourceLocation[1] - hostLocation[1]).toFloat()
            canvas.translate(pad + dx - source.scrollX, pad + dy - source.scrollY)
            visible.set(
                -dx + source.scrollX - pad,
                -dy + source.scrollY - pad,
                -dx + source.scrollX + w + pad,
                -dy + source.scrollY + h + pad
            )
            drawTree(source, canvas, visible)
        } finally {
            node.endRecording()
        }
    }

    /**
     * 遍历子树直接绘制可见叶 View，不引用子 View 自身的硬件 RenderNode，
     * 避免嵌套 RenderNode 在 RenderEffect 模糊层中产生错位或裁切异常。
     */
    private fun drawTree(v: View, canvas: Canvas, area: RectF) {
        if (v === host || v.visibility != View.VISIBLE || v.alpha <= 0f ||
            v.getTag(R.id.fork_glass_exclude_from_snapshot) == true
        ) return
        val left = v.scrollX.toFloat()
        val top = v.scrollY.toFloat()
        if (!RectF.intersects(area, RectF(left, top, left + v.width, top + v.height))) return
        if (v is ViewGroup) {
            v.background?.let { bg ->
                canvas.save()
                canvas.translate(left, top)
                bg.draw(canvas)
                canvas.restore()
            }
            val clipPadding = v.clipToPadding &&
                (v.paddingLeft != 0 || v.paddingTop != 0 || v.paddingRight != 0 || v.paddingBottom != 0)
            val clipSave = if (clipPadding) {
                canvas.save().also {
                    canvas.clipRect(
                        left + v.paddingLeft,
                        top + v.paddingTop,
                        left + v.width - v.paddingRight,
                        top + v.height - v.paddingBottom
                    )
                }
            } else -1
            for (i in 0 until v.childCount) {
                val child = v.getChildAt(i)
                val tx = child.left + child.translationX - child.scrollX
                val ty = child.top + child.translationY - child.scrollY
                canvas.save()
                canvas.translate(tx, ty)
                drawTree(
                    child,
                    canvas,
                    RectF(area.left - tx, area.top - ty, area.right - tx, area.bottom - ty)
                )
                canvas.restore()
            }
            if (clipSave >= 0) canvas.restoreToCount(clipSave)
        } else {
            v.draw(canvas)
        }
    }

    /** 宿主挪了位置（比如跟着楼层气泡走）后立即按新位置重录 */
    fun refresh() {
        record()
        invalidateSelf()
    }

    private fun edgePad(): Int = (appliedBlurDp * density * 2).toInt().coerceIn(0, 200)

    override fun draw(canvas: Canvas) {
        val b = bounds
        val save = canvas.save()
        if (oval) {
            clip.reset()
            clip.addOval(RectF(b), Path.Direction.CW)
            canvas.clipPath(clip)
        } else if (cornerRadius > 0f) {
            val r = minOf(cornerRadius, b.width() / 2f, b.height() / 2f)
            clip.reset()
            clip.addRoundRect(RectF(b), r, r, Path.Direction.CW)
            canvas.clipPath(clip)
        } else if (bottomCornerRadius > 0f) {
            val r = minOf(bottomCornerRadius, b.width() / 2f, b.height().toFloat())
            clip.reset()
            clip.addRoundRect(RectF(b), floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r), Path.Direction.CW)
            canvas.clipPath(clip)
        } else {
            canvas.clipRect(b)
        }
        if (canvas.isHardwareAccelerated) {
            if (!node.hasDisplayList()) record()
            canvas.save()
            canvas.translate(-recordedPad.toFloat(), -recordedPad.toFloat())
            canvas.drawRenderNode(node)
            canvas.restore()
            canvas.drawColor(tint)
        } else {
            canvas.drawColor(tintColor)
        }
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
