package com.github.ykrank.androidtools.widget.recycleview

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearSmoothScroller

/**LinearLayoutManager only snap to start
 * Created by ykrank on 2017/6/4.
 */
class StartSnapLinearLayoutManager : LinearLayoutManager {
    val context: Context

    constructor(context: Context) : super(context) {
        this.context = context
    }

    constructor(context: Context, orientation: Int, reverseLayout: Boolean) : super(context, orientation, reverseLayout) {
        this.context = context
    }

    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int, defStyleRes: Int) : super(context, attrs, defStyleAttr, defStyleRes) {
        this.context = context
    }

    /**
     * Fork：为 true 时忽略子 View 获得焦点引起的滚动（见 app 里的 SelectableTextFocusFix）
     */
    var blockFocusScroll = false

    override fun requestChildRectangleOnScreen(
        parent: RecyclerView, child: View, rect: Rect,
        immediate: Boolean, focusedChildVisible: Boolean
    ): Boolean {
        if (blockFocusScroll) return false
        return super.requestChildRectangleOnScreen(parent, child, rect, immediate, focusedChildVisible)
    }

    override fun onRequestChildFocus(
        parent: RecyclerView, state: RecyclerView.State, child: View, focused: View?
    ): Boolean {
        // true: 已处理，RecyclerView 不再为了显示获得焦点的子 View 而滚动
        if (blockFocusScroll) return true
        return super.onRequestChildFocus(parent, state, child, focused)
    }

    /**
     * Smooth scroll to the specified adapter position
     */
    fun smoothScrollToPosition(position: Int) {
        smoothScrollToPosition(position, 0)
    }

    /**
     * Smooth scroll to the specified adapter position with offset at start
     */
    fun smoothScrollToPosition(position: Int, offset: Int) {
        val scroller = StartSnapLinearSmoothScroller(context)
        scroller.targetPosition = position
        scroller.offset = offset
        startSmoothScroll(scroller)
    }
}

class StartSnapLinearSmoothScroller(context: Context?) : LinearSmoothScroller(context) {
    var offset: Int = 0

    override fun getVerticalSnapPreference(): Int {
        return SNAP_TO_START
    }

    override fun getHorizontalSnapPreference(): Int {
        return SNAP_TO_START
    }

    override fun calculateDtToFit(viewStart: Int, viewEnd: Int, boxStart: Int, boxEnd: Int, snapPreference: Int): Int {
        //snapPreference always SNAP_TO_START
        if (snapPreference == SNAP_TO_START) {
            return boxStart - viewStart + offset
        }
        throw IllegalArgumentException("snap preference should be SNAP_TO_START")
    }
}