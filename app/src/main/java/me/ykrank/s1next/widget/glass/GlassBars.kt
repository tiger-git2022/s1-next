package me.ykrank.s1next.widget.glass

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.AppCompatSpinner
import androidx.appcompat.widget.ListPopupWindow
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
 * 论坛列表、帖子列表、帖子页的顶栏改成玻璃（帖子列表不再显示子版块标签；帖子列表和帖子页上滑时收起标题栏）：内容一直延伸到顶栏和状态栏下面，
 * 顶栏背景是下面内容的模糊加半透明染色（[GlassBarDrawable]）。列表顶部留出顶栏高度的内边距，
 * 下拉刷新圈和右侧快速索引一起下移。只在 Android 12 及以上开启（需要 RenderEffect），更低版本保持原样。
 */
object GlassBars {

    private val ENABLED_ACTIVITIES: Set<Class<out Activity>> = setOf(
        ForumActivity::class.java,
        ThreadListActivity::class.java,
        PostListActivity::class.java,
    )

    /**
     * 顶栏（含状态栏）的高度，列表据此留出顶部内边距；
     * 帖子列表和帖子页上滑时收起标题栏（状态栏那条玻璃固定不动），下滑或回到顶部时放出。
     */
    class State(
        internal val content: View,
        private val appBar: AppBarLayout,
        internal val tintColor: Int,
        internal val backdropColor: Int,
        private val autoHide: Boolean,
    ) {
        var topInset: Int = 0
            private set
        private val listeners = mutableListOf<(Int) -> Unit>()
        private val barListeners = mutableListOf<(Boolean) -> Unit>()
        private var barShown = true

        /** 列表滚动 dy 像素：上滑（dy > 0）收起标题栏，下滑放出，到顶时全显示 */
        internal fun onListScrolled(recyclerView: RecyclerView, dy: Int) {
            if (!autoHide) return
            appBar.animate().cancel()
            val max = appBar.height.toFloat()
            val t = if (!recyclerView.canScrollVertically(-1)) 0f
            else (appBar.translationY - dy).coerceIn(-max, 0f)
            appBar.translationY = t
            notifyBar(t > -max / 2)
        }

        /** 停止滚动时吸附到全显示或全收起 */
        internal fun onListIdle(recyclerView: RecyclerView) {
            if (!autoHide) return
            val show = !recyclerView.canScrollVertically(-1) || appBar.translationY > -appBar.height / 2f
            appBar.animate().translationY(if (show) 0f else -appBar.height.toFloat()).setDuration(160).start()
            notifyBar(show)
        }

        private fun notifyBar(shown: Boolean) {
            if (shown == barShown) return
            barShown = shown
            barListeners.toList().forEach { it(shown) }
        }

        internal fun observeBar(listener: (Boolean) -> Unit) {
            barListeners.add(listener)
        }

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

    /**
     * 下拉刷新的转圈按钮：去掉阴影和白底，换成和悬浮按钮一样的圆形玻璃。
     * 转圈按钮是 SwipeRefreshLayout 自己加的子 View（CircleImageView，继承 ImageView）。
     */
    private fun glassRefresh(refresh: SwipeRefreshLayout, recyclerView: RecyclerView, state: State) {
        val circle = refresh.children.firstOrNull { it is android.widget.ImageView } ?: return
        circle.elevation = 0f
        // 只模糊列表：转圈按钮自己就在内容区里，录整个内容区会把自己也录进去，渲染时无限递归闪退
        circle.background = GlassBarDrawable(
            circle, recyclerView, state.tintColor, state.backdropColor, oval = true
        )
    }

    /** 夜间模式玻璃的颜色（深灰白） */
    internal val DARK_GLASS_TINT = Color.rgb(120, 120, 126)

    /** 顶栏下面两个角的圆角（dp），和自用 NGA 客户端一致 */
    private const val BAR_CORNER_DP = 20f

    /**
     * 列表被程序直接跳到某个位置后（楼层滚动条拖动），按是否在顶部同步玻璃标题栏和悬浮按钮的显示
     */
    fun onListJumped(recyclerView: RecyclerView) {
        stateOf(recyclerView.context.findActivity())?.onListIdle(recyclerView)
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

        // 夜间模式的顶栏颜色本身接近黑色，叠在深色内容上看不出玻璃；改成深灰白，比背景亮一档
        val tintColor = barColor(appBar).let {
            if (ColorUtils.calculateLuminance(it) < 0.5) DARK_GLASS_TINT else it
        }
        val backdropColor = windowBackgroundColor(activity)

        // 帖子列表不显示分类标签和分隔线，省出位置（左右滑动切换的是页码，不能再按分类筛选）
        (content as? ConstraintLayout)?.let { hideTabs(it) }

        // 内容铺满整个 CoordinatorLayout，从顶栏下面穿过；顶栏移到最上层，不被内容盖住
        (content.layoutParams as CoordinatorLayout.LayoutParams).behavior = null
        content.requestLayout()
        appBar.bringToFront()

        // 不用 AppBarLayout 自己的滚动收起（内容已不在它下面），改由列表滚动时平移（State.onListScrolled）；
        // 三个点菜单也用玻璃（文字颜色按玻璃深浅选浅色或深色主题）
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
        // 顶栏和内容都声明自己处理：顶栏放在状态栏下方（上外边距），状态栏那条是单独固定的玻璃条，
        // 标题栏收起时从它后面滑上去；内容什么都不做（铺到状态栏下面）
        val statusStrip = View(activity)
        // 状态栏始终带 40% 的顶栏配色，不跟设置里的不透明度走，图标一直看得清
        statusStrip.background = GlassBarDrawable(
            statusStrip, content, tintColor, backdropColor,
            statusBar = true,
        )
        coordinator.addView(statusStrip, CoordinatorLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0
        ).apply { gravity = Gravity.TOP })
        // 同样要声明自己处理系统边距，否则会被 CoordinatorLayout 下移一个状态栏高度，正好盖住标题栏
        statusStrip.fitsSystemWindows = true
        ViewCompat.setOnApplyWindowInsetsListener(statusStrip) { _, insets -> insets }
        appBar.fitsSystemWindows = true
        ViewCompat.setOnApplyWindowInsetsListener(appBar) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val lp = v.layoutParams as ViewGroup.MarginLayoutParams
            if (lp.topMargin != top) {
                lp.topMargin = top
                v.layoutParams = lp
            }
            val slp = statusStrip.layoutParams
            if (slp.height != top) {
                slp.height = top
                statusStrip.layoutParams = slp
            }
            insets
        }
        content.fitsSystemWindows = true
        ViewCompat.setOnApplyWindowInsetsListener(content) { _, insets -> insets }
        // 状态栏图标颜色跟随玻璃颜色深浅
        WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            .isAppearanceLightStatusBars = ColorUtils.calculateLuminance(tintColor) > 0.5

        appBar.stateListAnimator = null
        appBar.elevation = 0f
        // 顶栏下面两个角是圆角（上面接状态栏玻璃条，保持直角）
        appBar.background = GlassBarDrawable(
            appBar, content, tintColor, backdropColor,
            bottomCornerRadius = BAR_CORNER_DP * activity.resources.displayMetrics.density
        )

        val state = State(
            content, appBar, tintColor, backdropColor,
            autoHide = activity is ThreadListActivity || activity is PostListActivity,
        )
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
        // 和原版一样：标题栏收起时隐藏悬浮按钮，出来时显示
        state.observeBar { shown -> if (shown) fab.show() else fab.hide() }
        // 帖子详情：刚进去时不显示回复按钮，上滑收起标题栏后再下滑（标题栏重新出现）时才出来
        if (activity is PostListActivity) {
            fab.visibility = View.GONE
        }
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

    /**
     * 首页「全部 ▼」下拉框：玻璃背景，从顶栏下沿弹出（下拉框的弹窗默认从下拉框自身顶部开始，盖住顶栏）。
     */
    fun glassSpinner(spinner: AppCompatSpinner) {
        if (!isSupported()) return
        val activity = spinner.context.findActivity() ?: return
        stateOf(activity) ?: return
        val toolbar = activity.findViewById<Toolbar>(R.id.toolbar) ?: return
        spinner.setPopupBackgroundDrawable(GlassPopupDrawable())
        val spinnerLoc = IntArray(2)
        val toolbarLoc = IntArray(2)
        val updateOffset = {
            spinner.getLocationInWindow(spinnerLoc)
            toolbar.getLocationInWindow(toolbarLoc)
            spinner.dropDownVerticalOffset = (toolbarLoc[1] + toolbar.height) - spinnerLoc[1]
        }
        spinner.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateOffset() }
        spinner.post { updateOffset() }
    }

    /** 帖子列表「子版块」这类列表弹窗：玻璃背景，从顶栏下沿弹出 */
    fun glassListPopup(activity: Activity, popup: ListPopupWindow) {
        if (!isSupported() || stateOf(activity) == null) return
        popup.setBackgroundDrawable(GlassPopupDrawable())
    }

    /** 在 ListPopupWindow.show() 之前调用（锚点已设好）：让弹窗顶部对齐顶栏下沿 */
    fun alignBelowToolbar(activity: Activity, popup: ListPopupWindow) {
        if (!isSupported() || stateOf(activity) == null) return
        val anchor = popup.anchorView ?: return
        val toolbar = activity.findViewById<Toolbar>(R.id.toolbar) ?: return
        val a = IntArray(2)
        val t = IntArray(2)
        anchor.getLocationInWindow(a)
        toolbar.getLocationInWindow(t)
        popup.verticalOffset = (t[1] + toolbar.height) - (a[1] + anchor.height)
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

    private fun hideTabs(content: ConstraintLayout) {
        // ThreadListActivity 加载到分类后会把标签栏重新设为可见（refreshTabLayout），只设 GONE 的话
        // 它会重新出现在页面最上面，藏在玻璃顶栏后面透出来；直接移除又会让 ThreadListActivity
        // findViewById(R.id.tab) 拿不到而闪退。所以挪进一个始终 GONE 的空容器里：还能找到，但永远不显示
        val holder = android.widget.FrameLayout(content.context).apply { visibility = View.GONE }
        listOf(R.id.tab, R.id.separate).forEach { id ->
            content.findViewById<View>(id)?.let {
                content.removeView(it)
                holder.addView(it)
            }
        }
        content.addView(holder, ConstraintLayout.LayoutParams(0, 0))
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
        refresh?.let { glassRefresh(it, recyclerView, state) }
        val quickSideBar = fragment.view?.findViewById<QuickSideBarView>(R.id.quickSideBarView)
        val quickSideBarTop = (quickSideBar?.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
        val density = fragment.resources.displayMetrics.density

        // 「数据来自缓存」提示原本在列表上方，现在列表伸到顶栏下面，提示会被顶栏挡住：
        // 把它移到顶栏下方浮在列表上，显示时列表再往下让出它的高度
        val hint = fragment.view?.findViewById<TextView>(R.id.tv_hint)
        var topNow = 0
        fun hintSpace() = if (hint != null && hint.visibility == View.VISIBLE) hint.height else 0
        fun applyPadding() {
            val p = baseTop + topNow + hintSpace()
            if (recyclerView.paddingTop != p) {
                recyclerView.setPadding(recyclerView.paddingLeft, p, recyclerView.paddingRight, recyclerView.paddingBottom)
            }
        }
        if (hint != null && refresh != null) {
            (refresh.layoutParams as? ConstraintLayout.LayoutParams)?.let {
                it.topToBottom = ConstraintLayout.LayoutParams.UNSET
                it.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                refresh.layoutParams = it
            }
            // 一直显示到网络加载成功为止，所以做成顶栏下方一整行的细条，文字居中；放到最上层，不被列表盖住
            hint.background = ColorDrawable(ColorUtils.setAlphaComponent(state.backdropColor, 0xE6))
            hint.gravity = Gravity.CENTER
            hint.setPadding(0, (3 * density).toInt(), 0, (3 * density).toInt())
            (hint.layoutParams as ViewGroup.MarginLayoutParams).let {
                it.width = 0 // 左右约束到父布局，铺满整行
                it.topMargin = 0
                hint.layoutParams = it
            }
            hint.bringToFront()
        }
        // 这一行算列表顶部的一部分：往下翻时跟着内容一起滚走（滚到顶栏后面），回到顶部才再出现。
        // 按第一项相对列表顶部留白的位置算，比 computeVerticalScrollOffset 的估算准
        fun followList() {
            val h = hint ?: return
            // 不用 getChildAt(0)：刷新数据时列表在做动画，第一个子 View 可能是正在消失的旧项
            val first = recyclerView.layoutManager?.findViewByPosition(0)
            h.translationY = when {
                first != null -> (first.top - recyclerView.paddingTop).coerceAtMost(0).toFloat()
                (recyclerView.adapter?.itemCount ?: 0) == 0 -> 0f
                else -> -(h.bottom + h.height).toFloat()
            }
        }
        // 提示显示/隐藏后（隐藏时不会触发它自己的 layout 回调）更新列表顶部的留白
        val globalLayout = android.view.ViewTreeObserver.OnGlobalLayoutListener {
            if (hint != null) {
                applyPadding()
                followList()
            }
        }
        recyclerView.viewTreeObserver.addOnGlobalLayoutListener(globalLayout)


        val remove = state.observe { top ->
            topNow = top
            applyPadding()
            hint?.let {
                val lp = it.layoutParams as ViewGroup.MarginLayoutParams
                val m = top
                if (lp.topMargin != m) {
                    lp.topMargin = m
                    it.layoutParams = lp
                }
            }
            refresh?.setProgressViewOffset(false, top - (40 * density).toInt(), top + (64 * density).toInt())
            quickSideBar?.let {
                val lp = it.layoutParams as ViewGroup.MarginLayoutParams
                lp.topMargin = quickSideBarTop + top
                it.layoutParams = lp
            }
        }
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                followList()
                // 只跟手指拖动和惯性滑动；程序跳转（恢复阅读进度、翻页）不收起
                if (dy != 0 && recyclerView.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
                    state.onListScrolled(recyclerView, dy)
                }
            }

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) state.onListIdle(recyclerView)
            }
        })
        fragment.viewLifecycleOwner.lifecycle.addObserver(object : LifecycleEventObserver {
            override fun onStateChanged(source: androidx.lifecycle.LifecycleOwner, event: Lifecycle.Event) {
                if (event == Lifecycle.Event.ON_DESTROY) {
                    remove()
                    recyclerView.viewTreeObserver.removeOnGlobalLayoutListener(globalLayout)
                }
            }
        })
    }
}
