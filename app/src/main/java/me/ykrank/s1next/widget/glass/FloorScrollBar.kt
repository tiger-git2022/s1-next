package me.ykrank.s1next.widget.glass

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Fork（tiger-git2022）：帖子页右侧的楼层滚动条，替代原来右边一列楼层数字（快速侧边栏），移植自自用 NGA 客户端。
 *
 * 滑动时出现一根细滚动条，一停下就淡出并往右滑走；按住滑块拖动时，旁边的气泡显示拖到第几楼 / 全帖共几楼。
 * 按住右边缘的滑块拖动可在本页内快速跳到某一楼（翻页仍用左右滑动）。隐藏时不拦截任何触摸。
 */
@RequiresApi(Build.VERSION_CODES.S)
class FloorScrollBar private constructor(
    context: Context,
    private val recyclerView: RecyclerView,
    private val source: Source,
) : View(context) {

    interface Source {
        /** 列表第 position 项的楼层号（主楼为 1），未知时返回 -1 */
        fun floorAt(position: Int): Int

        /** 全帖最后一楼的楼层号，未知时返回 -1 */
        fun lastFloor(): Int
    }

    companion object {
        /** 停止滑动（或松开滑块）后多久开始淡出；留足时间让手指来得及按住滑块拖动 */
        private const val HIDE_DELAY = 1000L

        @JvmStatic
        fun attach(parent: ViewGroup, recyclerView: RecyclerView, source: Source): FloorScrollBar {
            val bar = FloorScrollBar(parent.context, recyclerView, source)
            // Fork: 气泡底下放一块跟着走的玻璃（模糊后面的帖子内容，染原来的深灰），文字由滚动条画在上面
            val glass = View(parent.context)
            parent.addView(glass, ViewGroup.LayoutParams(0, 0))
            val tv = TypedValue()
            parent.context.theme.resolveAttribute(android.R.attr.colorBackground, tv, true)
            glass.background = GlassBarDrawable(
                glass, recyclerView, BUBBLE_COLOR, tv.data, fixedOpacity = 70, cornerRadius = Float.MAX_VALUE
            )
            glass.visibility = INVISIBLE
            bar.bubbleGlass = glass
            parent.addView(
                bar, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            return bar
        }

        private val BUBBLE_COLOR = Color.rgb(48, 48, 48)
    }

    /** 气泡的玻璃底，每帧绘制前跟随气泡的位置、大小和整个滚动条的透明度、位移 */
    private var bubbleGlass: View? = null

    private val followBubble = ViewTreeObserver.OnPreDrawListener {
        bubbleGlass?.let { g ->
            if (bubbleRect.isEmpty || alpha <= 0f) {
                if (g.visibility != INVISIBLE) g.visibility = INVISIBLE
            } else {
                val w = bubbleRect.width().toInt()
                val h = bubbleRect.height().toInt()
                val lp = g.layoutParams
                if (lp.width != w || lp.height != h) {
                    lp.width = w
                    lp.height = h
                    g.layoutParams = lp
                }
                val x = left + bubbleRect.left + translationX
                val y = top + bubbleRect.top + translationY
                val moved = g.translationX != x || g.translationY != y
                g.translationX = x
                g.translationY = y
                g.alpha = alpha
                if (g.visibility != VISIBLE) g.visibility = VISIBLE
                // 玻璃自己的 OnPreDraw 可能已经按旧位置录过了，挪完立即按新位置重录
                if (moved) (g.background as? GlassBarDrawable)?.refresh()
            }
        }
        true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(followBubble)
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val touchWidth = dp(48f)
    private val thumbWidth = dp(5f)
    private val thumbWidthDragging = dp(8f)
    private val thumbHeight = dp(40f)
    private val trackWidth = dp(3f)
    private val edgeMargin = dp(3f)
    private val verticalMargin = dp(8f)

    private val accent = TypedValue().let {
        context.theme.resolveAttribute(androidx.appcompat.R.attr.colorAccent, it, true)
        it.data
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(40, 128, 128, 128) }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 48, 48, 48) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(12f)
        isFakeBoldText = true
    }
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 255, 255)
        textSize = sp(12f)
    }

    private val rect = RectF()

    /** 上一次画气泡的位置，按住气泡也能拖动 */
    private val bubbleRect = RectF()

    /** 按下时手指相对滑块中心的偏移，拖动时保持不变，按下那一下滑块不会跳 */
    private var grabOffset = 0f

    private var shown = false
    private var dragging = false

    /** 拖动时滑块中心的 y（相对本 View） */
    private var dragY = 0f

    private val hideRunnable = Runnable { if (!dragging) fadeOut() }

    init {
        alpha = 0f
        translationX = dp(16f)
        isClickable = false
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy != 0) {
                    show()
                }
                invalidate()
            }

            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    scheduleHide()
                } else {
                    removeCallbacks(hideRunnable)
                }
            }
        })
    }

    private fun canShow(): Boolean {
        val count = recyclerView.adapter?.itemCount ?: 0
        // 一屏放得下的页不需要滚动条
        return count > 1 && (recyclerView.canScrollVertically(1) || recyclerView.canScrollVertically(-1))
    }

    private fun show() {
        if (!canShow()) return
        removeCallbacks(hideRunnable)
        if (!shown) {
            shown = true
            animate().cancel()
            animate().alpha(1f).translationX(0f).setDuration(120).start()
        }
    }

    private fun scheduleHide() {
        removeCallbacks(hideRunnable)
        postDelayed(hideRunnable, HIDE_DELAY)
    }

    private fun fadeOut() {
        if (!shown) return
        shown = false
        clearGestureExclusion()
        animate().cancel()
        // 淡出的同时往右滑出
        animate().alpha(0f).translationX(dp(16f)).setDuration(220).start()
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(followBubble)
        removeCallbacks(hideRunnable)
        super.onDetachedFromWindow()
    }

    // 列表上下的内边距（玻璃标题栏、页码栏盖住的部分）不放滚动条
    private val trackTop get() = verticalMargin + recyclerView.paddingTop
    private val trackBottom get() = height - verticalMargin - recyclerView.paddingBottom

    /**
     * 当前滚动位置对应的 0..1，按楼数算而不是按像素：
     * 上方已滑过的楼数 ÷（上方已滑过的 + 下方还没看到的）。屏幕外的楼不管多高都算 1，
     * 所以图片加载、楼层高度估算变化时滑块不会跳；顶部为 0、底部为 1，中间连续。
     */
    private fun scrollFraction(): Float {
        val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return 0f
        val count = recyclerView.adapter?.itemCount ?: 0
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        if (count <= 1 || first < 0 || last < first) return 0f
        val top = recyclerView.paddingTop
        val bottom = recyclerView.height - recyclerView.paddingBottom
        val firstView = lm.findViewByPosition(first)
        val lastView = lm.findViewByPosition(last)
        val hiddenTop = firstView?.takeIf { it.height > 0 }
            ?.let { ((top - it.top).toFloat() / it.height).coerceIn(0f, 1f) } ?: 0f
        val hiddenBottom = lastView?.takeIf { it.height > 0 }
            ?.let { ((it.bottom - bottom).toFloat() / it.height).coerceIn(0f, 1f) } ?: 0f
        val above = first + hiddenTop
        val below = (count - 1 - last) + hiddenBottom
        if (above + below <= 0f) return 0f
        return (above / (above + below)).coerceIn(0f, 1f)
    }

    private fun thumbCenterY(): Float {
        if (dragging) return dragY
        val travel = trackBottom - trackTop - thumbHeight
        return trackTop + thumbHeight / 2 + travel * scrollFraction()
    }

    private fun fractionForY(y: Float): Float {
        val travel = trackBottom - trackTop - thumbHeight
        if (travel <= 0) return 0f
        return ((y - trackTop - thumbHeight / 2) / travel).coerceIn(0f, 1f)
    }

    private fun positionForFraction(fraction: Float): Int {
        val count = recyclerView.adapter?.itemCount ?: 0
        if (count <= 0) return -1
        return Math.round(fraction * (count - 1)).coerceIn(0, count - 1)
    }

    private fun scrollToFraction(fraction: Float) {
        val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val position = positionForFraction(fraction)
        if (position < 0) return
        recyclerView.stopScroll()
        if (fraction >= 1f) {
            // 拖到底：最后一楼可能比一屏还高，滚到列表真正的底部
            lm.scrollToPositionWithOffset(position, 0)
            recyclerView.post { recyclerView.scrollBy(0, recyclerView.computeVerticalScrollRange()) }
        } else {
            lm.scrollToPositionWithOffset(position, 0)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (alpha <= 0f || height <= 0) return
        val right = width - edgeMargin

        // 轨道
        rect.set(right - trackWidth, trackTop, right, trackBottom)
        canvas.drawRoundRect(rect, trackWidth / 2, trackWidth / 2, trackPaint)

        // 滑块
        val centerY = thumbCenterY()
        val w = if (dragging) thumbWidthDragging else thumbWidth
        rect.set(right - w, centerY - thumbHeight / 2, right, centerY + thumbHeight / 2)
        canvas.drawRoundRect(rect, w / 2, w / 2, thumbPaint)

        // 气泡：只在按住滑块拖动时显示（平时滑动浏览只有一根滚动条）
        val position = if (dragging) positionForFraction(fractionForY(dragY)) else -1
        val floor = if (position >= 0) source.floorAt(position) else -1
        if (floor <= 0) {
            bubbleRect.setEmpty()
            return
        }
        // 一行紧凑显示：「12/33」，主楼（1 楼）显示「主楼/33」
        val last = source.lastFloor()
        val current = if (floor == 1) "主楼" else floor.toString()
        val total = if (last > 0) "/$last" else ""

        val padH = dp(7f)
        val padV = dp(3f)
        val currentW = textPaint.measureText(current)
        val totalW = subTextPaint.measureText(total)
        val textH = textPaint.fontMetrics.let { it.descent - it.ascent }
        val bw = currentW + totalW + padH * 2
        val bh = textH + padV * 2
        val bRight = right - thumbWidthDragging - dp(6f)
        val bTop = (centerY - bh / 2).coerceIn(0f, (height - bh).coerceAtLeast(0f))
        rect.set(bRight - bw, bTop, bRight, bTop + bh)
        bubbleRect.set(rect)
        // 有玻璃底时底色由玻璃画，这里只画文字
        if (bubbleGlass == null) canvas.drawRoundRect(rect, bh / 2, bh / 2, bubblePaint)
        updateGestureExclusion(centerY)
        val baseline = bTop + padV - textPaint.fontMetrics.ascent
        canvas.drawText(current, rect.left + padH, baseline, textPaint)
        canvas.drawText(total, rect.left + padH + currentW, baseline, subTextPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 只有显示出来时、按在滑块或气泡上才接管，其余触摸（比如每楼右边的菜单按钮）照常交给帖子列表
                if (!shown || alpha < 0.5f) {
                    return false
                }
                val centerY = thumbCenterY()
                val onThumb = event.x >= width - touchWidth && Math.abs(event.y - centerY) <= thumbHeight * 1.5f
                val slop = dp(8f)
                val onBubble = event.x >= bubbleRect.left - slop && event.x <= bubbleRect.right + slop &&
                        event.y >= bubbleRect.top - slop && event.y <= bubbleRect.bottom + slop
                if (!onThumb && !onBubble) {
                    return false
                }
                // 按住时停止惯性滑动，并保持显示
                recyclerView.stopScroll()
                shown = true
                animate().cancel()
                alpha = 1f
                translationX = 0f
                dragging = true
                grabOffset = event.y - centerY
                dragY = centerY
                parent?.requestDisallowInterceptTouchEvent(true)
                removeCallbacks(hideRunnable)
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    dragTo(event.y - grabOffset)
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    invalidate()
                    scheduleHide()
                    // 拖动是直接跳位置，不会带动标题栏；松手后按是否回到顶部同步一下
                    recyclerView.post { syncAppBar() }
                    return true
                }
            }
        }
        return dragging
    }

    private val exclusionRect = Rect()
    private var exclusionActive = false

    /**
     * 全面屏手势下屏幕右边缘是系统「返回」手势区，按在滑块上会被系统当成返回。
     * 显示时把滑块和气泡那一小块从返回手势里排除（系统限制每边总高 200dp，这里不到 80dp），隐藏后还给系统。
     */
    private fun updateGestureExclusion(centerY: Float) {
        val left = minOf(bubbleRect.left, width - touchWidth).toInt()
        val half = (thumbHeight / 2 + dp(8f)).toInt()
        exclusionRect.set(left, (centerY - half).toInt(), width, (centerY + half).toInt())
        ViewCompat.setSystemGestureExclusionRects(this, listOf(exclusionRect))
        exclusionActive = true
    }

    private fun clearGestureExclusion() {
        if (exclusionActive) {
            exclusionActive = false
            ViewCompat.setSystemGestureExclusionRects(this, emptyList())
        }
    }

    /** 拖动是直接跳位置，不会带动玻璃标题栏；松手后按是否回到顶部同步一下 */
    private fun syncAppBar() {
        GlassBars.onListJumped(recyclerView)
    }

    private fun dragTo(y: Float) {
        dragY = y.coerceIn(trackTop + thumbHeight / 2, trackBottom - thumbHeight / 2)
        scrollToFraction(fractionForY(dragY))
        invalidate()
    }
}
