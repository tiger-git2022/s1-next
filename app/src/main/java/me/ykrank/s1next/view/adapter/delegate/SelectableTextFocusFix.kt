package me.ykrank.s1next.view.adapter.delegate

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.github.ykrank.androidtools.widget.recycleview.StartSnapLinearLayoutManager

/**
 * Fork（tiger-git2022）：开启「文本可选」后，楼层滑进屏幕时会把 TextView 禁用再启用
 * （绕开文本被复用后失去可选能力的旧 bug）。可选的 TextView 能获得焦点，重新启用时系统把焦点给它，
 * 它再要求列表滚动到自己：翻页后被拉回顶部、阅读位置被拉走，在布局过程中触发时还会崩溃
 * （Cannot call this method while RecyclerView is computing a layout or scrolling）。
 * 这期间让列表忽略焦点引起的滚动。焦点到滚动是同步发生的，所以只需在调用前后打开开关。
 */
object SelectableTextFocusFix {

    inline fun withoutFocusScroll(itemView: View, block: () -> Unit) {
        val layoutManager =
            (itemView.parent as? RecyclerView)?.layoutManager as? StartSnapLinearLayoutManager
        val old = layoutManager?.blockFocusScroll ?: false
        layoutManager?.blockFocusScroll = true
        try {
            block()
        } finally {
            layoutManager?.blockFocusScroll = old
        }
    }
}
