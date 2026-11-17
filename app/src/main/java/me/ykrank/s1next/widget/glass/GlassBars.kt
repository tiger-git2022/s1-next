package me.ykrank.s1next.widget.glass

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.bigkoo.quicksidebar.QuickSideBarView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.tabs.TabLayout
import me.ykrank.s1next.R
import me.ykrank.s1next.view.activity.ForumActivity
import me.ykrank.s1next.view.activity.ThreadListActivity
import me.ykrank.s1next.view.page.post.postlist.PostListActivity

/**
 * Fork（tiger-git2022）：玻璃顶栏。
 *
 * 论坛列表、帖子列表、帖子页的顶栏改成玻璃（帖子列表不再显示子版块标签）：内容一直延伸到顶栏和状态栏下面，
 * 顶栏背景是下面内容的模糊加半透明染色（[GlassBarDrawable]）。列表顶部留出顶栏高度的内边距，
 * 下拉刷新圈和右侧快速索引一起下移。只在 Android 12 及以上开启（需要 RenderEffect），更低版本保持原样。
 */
object GlassBars {

    private val ENABLED_ACTIVITIES: Set<Class<out Activity>> = setOf(
        ForumActivity::class.java,
        ThreadListActivity::class.java,
        PostListActivity::class.java,
    )

    /** 顶栏（含状态栏）的高度，列表据此留出顶部内边距 */
    class State(
        internal val content: View,
        internal val tintColor: Int,
        internal val backdropColor: Int,
    ) {
        var topInset: Int = 0
            private set
        private val listeners = mutableListOf<(Int) -> Unit>()

        fun observe(listener: (Int) -> Unit): () -> Unit {
            listeners.add(listener)
            if (topInset > 0) listener(topInset)
            return { listeners.remove(listener) }
        }

        internal fun update(value: Int) {
            if (value == topInset) return
            topInset = value
            listeners.toList().forEach { it(value) }
        }
    }

    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    private fun stateOf(activity: Activity?): State? =
        activity?.window?.decorView?.getTag(R.id.fork_glass_state) as? State

    /** 玻璃顶栏的颜色，给弹出菜单（GlassPopupDrawable）用；没开玻璃时为 null */
    fun tintColorOf(activity: Activity): Int? = stateOf(activity)?.tintColor

    /**
     * 在 setContentView 之后调用（BaseActivity.setupToolbar）。
     */
    fun install(activity: Activity) {
        if (!isSupported() || !ENABLED_ACTIVITIES.contains(activity.javaClass)) return
        if (stateOf(activity) != null) return
        val coordinator = activity.findViewById<CoordinatorLayout>(R.id.coordinator_layout) ?: return
        val appBar = coordinator.children.filterIsInstance<AppBarLayout>().firstOrNull() ?: return
        val content = coordinator.children.firstOrNull {
            (it.layoutParams as? CoordinatorLayout.LayoutParams)?.behavior is AppBarLayout.ScrollingViewBehavior
        } ?: return

        val tintColor = barColor(appBar)
        val backdropColor = windowBackgroundColor(activity)

        // 帖子列表不显示子版块（分类）标签和分隔线，省出位置；左右滑动仍可切换分类
        (content as? ConstraintLayout)?.let { hideTabs(it) }

        // 内容铺满整个 CoordinatorLayout，从顶栏下面穿过；顶栏移到最上层，不被内容盖住
        (content.layoutParams as CoordinatorLayout.LayoutParams).behavior = null
        content.requestLayout()
        appBar.bringToFront()

        // 顶栏固定，不再随滚动收起；三个点菜单也用玻璃（文字颜色按玻璃深浅选浅色或深色主题）
        appBar.findViewById<Toolbar>(R.id.toolbar)?.let { toolbar ->
            (toolbar.layoutParams as? AppBarLayout.LayoutParams)?.scrollFlags = 0
            toolbar.popupTheme = if (ColorUtils.calculateLuminance(tintColor) > 0.5)
                R.style.Fork_ThemeOverlay_GlassPopup_Light else R.style.Fork_ThemeOverlay_GlassPopup_Dark
        }

        // 延伸到状态栏下面：DrawerLayout 不再画状态栏底色，也不再给 CoordinatorLayout 加上边距，
        // 由玻璃顶栏自己留出状态栏高度
        activity.findViewById<DrawerLayout>(R.id.drawer_layout)
            ?.setStatusBarBackgroundColor(Color.TRANSPARENT)
        coordinator.fitsSystemWindows = true
        coordinator.statusBarBackground = null
        // CoordinatorLayout 会把没有声明 fitsSystemWindows 的子 View 整体下移一个状态栏高度；
        // 顶栏和内容都声明自己处理：顶栏加状态栏高度的内边距，内容什么都不做（铺到状态栏下面）
        appBar.fitsSystemWindows = true
        ViewCompat.setOnApplyWindowInsetsListener(appBar) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(v.paddingLeft, top, v.paddingRight, v.paddingBottom)
            insets
        }
        content.fitsSystemWindows = true
        ViewCompat.setOnApplyWindowInsetsListener(content) { _, insets -> insets }
        // 状态栏图标颜色跟随玻璃颜色深浅
        WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            .isAppearanceLightStatusBars = ColorUtils.calculateLuminance(tintColor) > 0.5

        appBar.stateListAnimator = null
        appBar.elevation = 0f
        appBar.background = GlassBarDrawable(appBar, content, tintColor, backdropColor)

        val state = State(content, tintColor, backdropColor)
        activity.window.decorView.setTag(R.id.fork_glass_state, state)
        appBar.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> state.update(v.bottom) }
        ViewCompat.requestApplyInsets(coordinator)
    }

    /**
     * 悬浮按钮改成圆形玻璃：按钮本身透明，后面放一个同样大小的玻璃圆，每帧跟随按钮的位置、缩放、透明度和可见性。
     * （FloatingActionButton 不支持自定义背景，所以用跟随的 View。）
     */
    fun glassFab(fab: FloatingActionButton) {
        val activity = fab.context as? Activity ?: return
        val state = stateOf(activity) ?: return
        val parent = fab.parent as? ViewGroup ?: return
        val light = ColorUtils.calculateLuminance(state.tintColor) > 0.5

        fab.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        fab.compatElevation = 0f
        fab.imageTintList = ColorStateList.valueOf(if (light) 0xDE000000.toInt() else Color.WHITE)

        val glass = View(activity)
        glass.background = GlassBarDrawable(
            glass, state.content, state.tintColor, state.backdropColor, oval = true
        )
        parent.addView(glass, parent.indexOfChild(fab), CoordinatorLayout.LayoutParams(0, 0).apply {
            gravity = Gravity.TOP or Gravity.START
        })
        fab.viewTreeObserver.addOnPreDrawListener {
            val lp = glass.layoutParams
            if (lp.width != fab.width || lp.height != fab.height) {
                lp.width = fab.width
                lp.height = fab.height
                glass.layoutParams = lp
            }
            glass.x = fab.x
            glass.y = fab.y
            glass.scaleX = fab.scaleX
            glass.scaleY = fab.scaleY
            glass.alpha = fab.alpha
            glass.visibility = fab.visibility
            true
        }
    }

    private fun hideTabs(content: ConstraintLayout) {
        content.findViewById<TabLayout>(R.id.tab)?.visibility = View.GONE
        content.findViewById<View>(R.id.separate)?.visibility = View.GONE
        content.findViewById<View>(R.id.frame_layout)?.let {
            val lp = it.layoutParams as ConstraintLayout.LayoutParams
            lp.topToBottom = ConstraintLayout.LayoutParams.UNSET
            lp.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            it.layoutParams = lp
        }
    }

    private fun barColor(appBar: AppBarLayout): Int {
        when (val bg = appBar.background) {
            is MaterialShapeDrawable -> bg.fillColor?.defaultColor?.let { return it }
            is ColorDrawable -> return bg.color
        }
        val tv = TypedValue()
        appBar.context.theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, tv, true)
        return tv.data
    }

    private fun windowBackgroundColor(activity: Activity): Int {
        val tv = TypedValue()
        activity.theme.resolveAttribute(android.R.attr.colorBackground, tv, true)
        return tv.data
    }

    /**
     * 在列表页 onViewCreated 时调用：列表顶部留出顶栏高度，下拉刷新圈和快速索引一起下移。
     */
    fun applyListInsets(fragment: Fragment, recyclerView: RecyclerView) {
        val state = stateOf(fragment.activity) ?: return
        val baseTop = recyclerView.paddingTop
        recyclerView.clipToPadding = false
        val refresh = generateSequence(recyclerView.parent) { it.parent }
            .filterIsInstance<SwipeRefreshLayout>().firstOrNull()
        val quickSideBar = fragment.view?.findViewById<QuickSideBarView>(R.id.quickSideBarView)
        val quickSideBarTop = (quickSideBar?.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
        val density = fragment.resources.displayMetrics.density

        val remove = state.observe { top ->
            recyclerView.setPadding(
                recyclerView.paddingLeft, baseTop + top, recyclerView.paddingRight, recyclerView.paddingBottom
            )
            refresh?.setProgressViewOffset(false, top - (40 * density).toInt(), top + (64 * density).toInt())
            quickSideBar?.let {
                val lp = it.layoutParams as ViewGroup.MarginLayoutParams
                lp.topMargin = quickSideBarTop + top
                it.layoutParams = lp
            }
        }
        fragment.viewLifecycleOwner.lifecycle.addObserver(object : LifecycleEventObserver {
            override fun onStateChanged(source: androidx.lifecycle.LifecycleOwner, event: Lifecycle.Event) {
                if (event == Lifecycle.Event.ON_DESTROY) remove()
            }
        })
    }
}
