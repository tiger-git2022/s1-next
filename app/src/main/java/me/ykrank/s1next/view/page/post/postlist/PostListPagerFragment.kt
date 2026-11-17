package me.ykrank.s1next.view.page.post.postlist

import android.content.Context
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.util.Pair
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.lifecycleScope
import com.bigkoo.quicksidebar.QuickSideBarView
import com.bigkoo.quicksidebar.listener.OnQuickSideBarTouchListener
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.ykrank.androidautodispose.AndroidRxDispose
import com.github.ykrank.androidlifecycle.event.FragmentEvent
import com.github.ykrank.androidtools.data.Resource
import com.github.ykrank.androidtools.ui.internal.LoadingViewModelBindingDelegate
import com.github.ykrank.androidtools.util.L
import com.github.ykrank.androidtools.util.LooperUtil
import com.github.ykrank.androidtools.util.MathUtil
import com.github.ykrank.androidtools.util.RxJavaUtil
import com.github.ykrank.androidtools.widget.recycleview.StartSnapLinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import io.reactivex.Single
import io.reactivex.disposables.Disposable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ykrank.s1next.App
import me.ykrank.s1next.R
import me.ykrank.s1next.data.api.app.AppService
import me.ykrank.s1next.data.api.app.model.AppPostsWrapper
import me.ykrank.s1next.data.api.model.Post
import me.ykrank.s1next.data.api.model.Thread
import me.ykrank.s1next.data.api.model.collection.Posts
import me.ykrank.s1next.data.api.model.wrapper.PostsWrapper
import me.ykrank.s1next.data.db.biz.ReadProgressBiz
import me.ykrank.s1next.data.db.dbmodel.ReadProgress
import me.ykrank.s1next.data.pref.GeneralPreferencesManager
import me.ykrank.s1next.databinding.FragmentBaseWithQuickSideBarBinding
import me.ykrank.s1next.view.event.BlackListChangeEvent
import me.ykrank.s1next.view.event.PostSelectableChangeEvent
import me.ykrank.s1next.view.event.QuickSidebarEnableChangeEvent
import me.ykrank.s1next.view.fragment.BaseRecyclerViewFragment
import me.ykrank.s1next.view.internal.LoadingViewModelBindingDelegateQuickSidebarImpl
import me.ykrank.s1next.view.internal.PagerScrollState
import me.ykrank.s1next.view.page.app.AppPostListActivity
import me.ykrank.s1next.view.page.post.adapter.PostListRecyclerViewAdapter
import me.ykrank.s1next.view.page.post.postlist.PostListPagerFragment.PagerCallback
import me.ykrank.s1next.util.JsonUtil
import java.util.*
import javax.inject.Inject

/**
 * A Fragment representing one of the pages of posts.
 *
 *
 * Activity or Fragment containing this must implement [PagerCallback].
 */
class PostListPagerFragment : BaseRecyclerViewFragment<PostsWrapper>(),
    OnQuickSideBarTouchListener {

    @Inject
    internal lateinit var mGeneralPreferencesManager: GeneralPreferencesManager

    @Inject
    internal lateinit var objectMapper: ObjectMapper

    @Inject
    internal lateinit var appService: AppService

    /**
     * Fork：自动黑魔法，被屏蔽楼层 pid -> 通过 App 接口拿到的原文
     */
    private val unbannedReplies = HashMap<Int, String>()
    private var autoDarkMagicJob: Job? = null

    private var mThreadId: String? = null
    private var mPageNum: Int = 0

    /**
     * Only see this author, or all if null
     */
    private var mAuthorId: String? = null

    /**
     * 之前记录的阅读进度
     */
    private var readProgress: ReadProgress? = null
    private var scrollState: PagerScrollState? = null
    private var blacklistChanged = false

    private lateinit var binding: FragmentBaseWithQuickSideBarBinding
    private lateinit var mRecyclerView: androidx.recyclerview.widget.RecyclerView
    private lateinit var mRecyclerAdapter: PostListRecyclerViewAdapter
    private lateinit var mLayoutManager: StartSnapLinearLayoutManager
    private lateinit var quickSideBarView: QuickSideBarView
    private lateinit var quickSideBarTipsView: TextView
    private val letters = HashMap<String, Int>()

    private var mPagerCallback: PagerCallback? = null

    private var refreshAfterBlacklistChangeDisposable: Disposable? = null

    val pageNum: Int
        get() = mPageNum

    override fun onAttach(context: Context) {
        super.onAttach(context)
        App.appComponent.inject(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mPagerCallback = parentFragment as PagerCallback

        val bundle = requireArguments()
        mThreadId = bundle.getString(ARG_THREAD_ID)
        mPageNum = bundle.getInt(ARG_PAGE_NUM)
        mAuthorId = bundle.getString(ARG_AUTHOR_ID)
        if (readProgress == null) {
            readProgress = bundle.getParcelable(ARG_READ_PROGRESS)
            scrollState = bundle.getParcelable(ARG_PAGER_SCROLL_STATE)
        }
        leavePageMsg("PostListPagerFragment##ThreadId:$mThreadId,PageNum:$mPageNum")
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        mRecyclerView = recyclerView
        mLayoutManager = StartSnapLinearLayoutManager(requireActivity())
        mRecyclerView.layoutManager = mLayoutManager
        // Fork：不在布局后把焦点还给之前的楼层，否则会再触发一次滚到该楼层（触屏上用不到）
        mRecyclerView.preserveFocusAfterLayout = false
        mRecyclerAdapter =
            PostListRecyclerViewAdapter(
                this,
                requireContext()
            )
        mRecyclerView.adapter = mRecyclerAdapter

        // add pull up to refresh to RecyclerView
        mRecyclerView.addOnScrollListener(object :
            androidx.recyclerview.widget.RecyclerView.OnScrollListener() {

            override fun onScrollStateChanged(
                recyclerView: androidx.recyclerview.widget.RecyclerView,
                newState: Int
            ) {
                if (!shouldPullUpToRefresh()) {
                    return
                }
                // Fork: onScrollStateChanged can be dispatched in the middle of a layout pass
                // (e.g. a TextView gaining focus while binding triggers requestChildRectangleOnScreen
                // -> smoothScrollBy). Inserting the footer item then throws
                // "Cannot call this method while RecyclerView is computing a layout or scrolling",
                // so defer the adapter change until the current pass has finished.
                recyclerView.post {
                    if (view != null && !recyclerView.isComputingLayout && shouldPullUpToRefresh()) {
                        startPullToRefresh()
                    }
                }
            }
        })

        quickSideBarView.setOnQuickSideBarTouchListener(this)

        mEventBus.get()
            .ofType(PostSelectableChangeEvent::class.java)
            .to(AndroidRxDispose.withObservable(this, FragmentEvent.DESTROY_VIEW))
            .subscribe({ mRecyclerAdapter.notifyDataSetChanged() }, { super.onError(it) })

        mEventBus.get()
            .ofType(QuickSidebarEnableChangeEvent::class.java)
            .to(AndroidRxDispose.withObservable(this, FragmentEvent.DESTROY_VIEW))
            .subscribe({ invalidateQuickSidebarVisible() }, { super.onError(it) })

        mEventBus.get()
            .ofType(BlackListChangeEvent::class.java)
            .to(AndroidRxDispose.withObservable(this, FragmentEvent.DESTROY_VIEW))
            .subscribe { startBlackListRefresh() }
    }

    override fun onDestroy() {
        RxJavaUtil.disposeIfNotNull(refreshAfterBlacklistChangeDisposable)
        mPagerCallback = null
        super.onDestroy()
    }

    override fun getLoadingViewModelBindingDelegateImpl(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): LoadingViewModelBindingDelegate {
        binding = DataBindingUtil.inflate(
            inflater,
            R.layout.fragment_base_with_quick_side_bar,
            container,
            false
        )
        binding.quickSidebarEnable = false
        quickSideBarView = binding.quickSideBarView
        quickSideBarTipsView = binding.quickSideBarViewTips
        return LoadingViewModelBindingDelegateQuickSidebarImpl(binding)
    }

    private fun shouldPullUpToRefresh(): Boolean {
        return !isPullUpToRefresh
                && mPageNum == mPagerCallback?.getTotalPages()
                && !isLoading
                && mRecyclerAdapter.itemCount != 0
                && !mRecyclerView.canScrollVertically(1)
    }

    override fun startPullToRefresh() {
        if (isPullUpToRefreshValid) {
            mRecyclerAdapter.showFooterProgress()
            super.startPullToRefresh()
        }
    }

    /**
     * 黑名单更改后刷新当前帖子列表
     */
    internal fun startBlackListRefresh() {
        blacklistChanged = true
        startPullToRefresh()
    }

    internal fun loadReadProgressInRecycleView(readProgress: ReadProgress, smooth: Boolean) {
        this.readProgress = readProgress
        if (scrollState == null) {
            scrollState = PagerScrollState()
            scrollState!!.state = PagerScrollState.BEFORE_SCROLL_POSITION
        }
        if (!isLoading) {
            var position = readProgress.position
            var offset = readProgress.offset
            if (position <= 0) {
                //if position invalid or first, offset should below zero
                position = 0
                if (offset > 0) {
                    offset = 0
                }
            }
            val totalItemCount = mRecyclerAdapter.itemCount
            if (totalItemCount <= position) {
                position = totalItemCount - 1
            }
            if (smooth) {
                mLayoutManager.smoothScrollToPosition(position, offset)
            } else {
                mLayoutManager.scrollToPositionWithOffset(position, offset)
            }
            // Fork: already applied, don't keep it as a pending progress (see curReadProgress)
            this.readProgress = null
            scrollState?.state = PagerScrollState.FREE
        }
    }

    /**
     * 保存当前阅读进度
     */
    internal fun saveReadProgress() {
        val readProgress = curReadProgress
        if (readProgress != null) {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    val dbWrapper = ReadProgressBiz.instance
                    dbWrapper.saveReadProgress(readProgress)
                }
                showShortText(R.string.save_read_progress_success)
            }
        }
    }

    internal val curReadProgress: ReadProgress?
        get() {
            // Fork：上次的进度还没恢复到列表上（数据还没加载完）就离开了：保留原进度，避免被覆盖成顶部
            val pendingProgress = readProgress
            if (pendingProgress != null && scrollState?.state == PagerScrollState.BEFORE_SCROLL_POSITION) {
                return pendingProgress
            }
            // 以前只要在加载中（先显示缓存、网络数据还没回来，或上拉加载更多）就不记录进度，
            // 此时退出会回到更早的旧进度。只要列表上已有帖子，就照常记录。
            if (!this::mRecyclerAdapter.isInitialized || mRecyclerAdapter.dataSet.none { it is Post }) {
                return null
            }
            val itemPosition = findNowItemPosition()
            if (itemPosition.first == androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                return null
            }
            return ReadProgress(
                mThreadId?.toInt()
                    ?: 0, mPageNum, itemPosition.first!!, itemPosition.second!!
            )
        }

    /**
     * 现在Item的位置

     * @return
     */
    private fun findNowItemPosition(): Pair<Int, Int> {
        val itemPosition = mLayoutManager.findFirstVisibleItemPosition()
        var offset = 0
        val view = mLayoutManager.findViewByPosition(itemPosition)
        if (view != null) {
            //See LinearSmoothScroller#calculateDyToMakeVisible
            val params = view.layoutParams as androidx.recyclerview.widget.RecyclerView.LayoutParams
            val top = mLayoutManager.getDecoratedTop(view) - params.topMargin
            val start = mLayoutManager.paddingTop

            offset = top - start
        }
        return Pair(itemPosition, offset)
    }

    override suspend fun getSource(loading: Int): Flow<Resource<PostsWrapper>> {
        return apiCacheProvider.getPostsWrapper(
            mThreadId ?: "", mPageNum, mAuthorId,
            ignoreCache = isIgnoreCache,
        ) { pid, rates ->
            mRecyclerAdapter.dataSet.filterIsInstance<Post>()
                .forEachIndexed { index, post ->
                    if (post.id == pid) {
                        post.rates = rates
                        mRecyclerAdapter.notifyItemChanged(index)
                    }
                }
        }
    }

    override fun onNextSuccess(resource: Resource.Success<PostsWrapper>) {
        super.onNextSuccess(resource)
        val data = resource.data
        val pullUpToRefresh = isPullUpToRefresh
        var postList: List<Post>? = null

        val posts = data?.data
        if (posts != null) {
            postList = posts.postList
        }

        // if user has logged out, has no permission to access this thread or this thread is invalid
        if (postList.isNullOrEmpty()) {
            if (pullUpToRefresh) {
                // mRecyclerAdapter.getItemCount() = 0
                // when configuration changes (like orientation changes)
                if (mRecyclerAdapter.itemCount != 0) {
                    mRecyclerAdapter.hideFooterProgress()
                }
            }

            if (resource.source.isNewData()) {
                val threadId = mThreadId ?: posts?.postListInfo?.id
                if (threadId != null) {
                    if (isAdded && userVisibleHint) {
                        showSnackbar(
                            data?.result?.message
                                ?: getString(R.string.message_load_error),
                            Snackbar.LENGTH_INDEFINITE,
                            R.string.click_to_cast_dark_magic, View.OnClickListener {
                                AppPostListActivity.start(
                                    requireContext(),
                                    threadId,
                                    mPageNum,
                                    null
                                )
                            }
                        )
                    }
                } else {
                    consumeResult(data?.result)
                }
            }
        } else {
            initQuickSidebar(mPageNum, postList)

            //Thread info must not null, or exception
            val postListInfo = posts?.postListInfo as Thread
            mRecyclerAdapter.setThreadInfo(postListInfo, mPageNum)

            posts.vote?.let {
                mRecyclerAdapter.setVoteInfo(it)
            }

            mRecyclerAdapter.diffNewDataSet(postList, true) {
                if (blacklistChanged) {
                    blacklistChanged = false
                } else if (pullUpToRefresh) {

                } else if (readProgress != null && scrollState?.state == PagerScrollState.BEFORE_SCROLL_POSITION) {
                    mLayoutManager.scrollToPositionWithOffset(
                        readProgress!!.position,
                        readProgress!!.offset
                    )
                    readProgress = null
                    scrollState!!.state = PagerScrollState.FREE
                } else {
                    val quotePostId = arguments?.getString(ARG_QUOTE_POST_ID)
                    if (!TextUtils.isEmpty(quotePostId)) {
                        for (i in postList.indices) {
                            if (quotePostId?.toInt() == postList[i].id) {
                                // scroll to post post
                                mLayoutManager.scrollToPositionWithOffset(i, 0)
                                break
                            }
                        }
                        // clear this argument after redirecting
                        arguments?.putString(ARG_QUOTE_POST_ID, null)
                    }
                }
                autoCastDarkMagic(postList)
            }

            mPagerCallback?.setThreadInfo(postListInfo)
            posts.threadAttachment?.let {
                mPagerCallback?.setupThreadAttachment(it)
            }
        }
    }

    /**
     * Fork：自动施放黑魔法。楼层作者被禁止时，网页接口只返回“内容自动屏蔽”，
     * 若已登录 App 接口，则直接用 App 接口取原文替换，无需手动点击按钮。
     */
    private fun autoCastDarkMagic(postList: List<Post>) {
        val threadId = mThreadId ?: return
        if (!mUser.isAppLogged || postList.none { it.banned }) {
            return
        }
        autoDarkMagicJob?.cancel()
        autoDarkMagicJob = lifecycleScope.launch {
            val replies = try {
                withContext(Dispatchers.IO) {
                    fetchUnbannedReplies(threadId, postList.filter { it.banned })
                }
            } catch (e: Exception) {
                L.e("AutoDarkMagic", e)
                return@launch
            }
            if (replies.isEmpty() || view == null) {
                return@launch
            }
            val newDataSet = mRecyclerAdapter.dataSet.map { item ->
                val reply = (item as? Post)?.takeIf { it.banned }?.let { replies[it.id] }
                if (item is Post && reply != null) {
                    item.clone().apply {
                        this.reply = reply
                        this.banned = false
                    }
                } else {
                    item
                }
            }
            mRecyclerAdapter.diffNewDataSet(newDataSet, false)
        }
    }

    /**
     * Fork：App 接口的分页大小可能与网页接口不同，先取同页，缺的再按楼层号换算页码补取（最多 3 页）。
     */
    private fun fetchUnbannedReplies(threadId: String, banned: List<Post>): Map<Int, String> {
        val missing = banned.map { it.id }.filterNot { unbannedReplies.containsKey(it) }.toMutableSet()
        val triedPages = mutableSetOf<Int>()
        var page = mPageNum
        while (missing.isNotEmpty() && triedPages.size < 3 && triedPages.add(page)) {
            val data = appService.getPostsWrapper(mUser.appSecureToken, threadId, page)
                .compose(JsonUtil.jsonSingleTransformer(AppPostsWrapper::class.java))
                .blockingGet()
                .data ?: break
            data.list.forEach { appPost ->
                val message = appPost.message
                if (appPost.pid in missing && !message.isNullOrBlank()) {
                    unbannedReplies[appPost.pid] = message
                    missing.remove(appPost.pid)
                }
            }
            val nextFloor = banned.firstOrNull { it.id in missing }?.number?.toIntOrNull()
            if (nextFloor == null || data.pageSize <= 0) {
                break
            }
            page = MathUtil.divide(nextFloor, data.pageSize)
        }
        return banned.mapNotNull { post -> unbannedReplies[post.id]?.let { post.id to it } }.toMap()
    }

    override fun onError(throwable: Throwable) {
        //网络请求失败下依然刷新黑名单
        if (blacklistChanged) {
            blacklistChanged = false
            RxJavaUtil.disposeIfNotNull(refreshAfterBlacklistChangeDisposable)
            val dataSet = mRecyclerAdapter.dataSet
            refreshAfterBlacklistChangeDisposable = Single.just(dataSet)
                .map { filterPostAfterBlacklistChanged(it) }
                .compose(RxJavaUtil.iOSingleTransformer())
                .subscribe({ mRecyclerAdapter.diffNewDataSet(it, false) }, { L.report(it) })
        } else if (isPullUpToRefresh) {
            mRecyclerAdapter.hideFooterProgress()
        }

        super.onError(throwable)
    }

    internal fun invalidateQuickSidebarVisible(): Boolean {
        val enable = mGeneralPreferencesManager.isQuickSideBarEnable
        binding.quickSidebarEnable = enable

        if (!isLoading) {
            //Post notify
            mRecyclerAdapter.notifyDataSetChanged()
        }
        return enable
    }

    private fun initQuickSidebar(page: Int, posts: List<Post>) {
        invalidateQuickSidebarVisible()
        val customLetters = ArrayList<String>()
        var i = 0
        posts.forEach {
            if (i >= 10 && i % 2 == 0) {
                // noop
            } else {
                it.number?.apply {
                    customLetters.add(this)
                    letters[this] = i
                }
            }
            i++
        }
        quickSideBarView.letters = customLetters
    }

    override fun onLetterChanged(letter: String, position: Int, y: Float) {
        quickSideBarTipsView.text = letter
        //有此key则获取位置并滚动到该位置
        if (letters.containsKey(letter)) {
            mLayoutManager.scrollToPositionWithOffset(letters[letter] ?: 0, 0)
        }
    }

    override fun onLetterTouching(touching: Boolean) {
        //        quickSideBarTipsView.setVisibility(touching ? View.VISIBLE : View.INVISIBLE);
    }

    interface PagerCallback {

        /**
         * Gets [android.support.v4.view.PagerAdapter.getCount].
         */
        fun getTotalPages(): Int

        fun setupThreadAttachment(threadAttachment: Posts.ThreadAttachment)

        fun setThreadInfo(thread: Thread?)
    }

    companion object {
        val TAG = PostListPagerFragment::class.simpleName
        private const val ARG_THREAD_ID = "thread_id"
        private const val ARG_PAGE_NUM = "page_num"
        private const val ARG_AUTHOR_ID = "author_id"
        private const val ARG_READ_PROGRESS = "read_progress"
        private const val ARG_PAGER_SCROLL_STATE = "pager_scroll_state"

        /**
         * Used for post post redirect.
         */
        private const val ARG_QUOTE_POST_ID = "quote_post_id"

        fun newInstance(threadId: String, pageNum: Int): PostListPagerFragment {
            return newInstance(threadId, pageNum, null, null, null, null)
        }

        fun newInstance(
            threadId: String,
            pageNum: Int,
            progress: ReadProgress,
            scrollState: PagerScrollState
        ): PostListPagerFragment {
            return newInstance(threadId, pageNum, null, null, progress, scrollState)
        }

        fun newInstance(threadId: String, pageNum: Int, postId: String): PostListPagerFragment {
            return newInstance(threadId, pageNum, null, postId, null, null)
        }

        fun newInstance(
            threadId: String, pageNum: Int, authorId: String?,
            postId: String?, progress: ReadProgress?, scrollState: PagerScrollState?
        ): PostListPagerFragment {
            val fragment = PostListPagerFragment()
            val bundle = Bundle()
            bundle.putString(ARG_THREAD_ID, threadId)
            if (!TextUtils.isEmpty(postId)) {
                bundle.putString(ARG_QUOTE_POST_ID, postId)
            }
            bundle.putString(ARG_AUTHOR_ID, authorId)
            bundle.putInt(ARG_PAGE_NUM, pageNum)
            bundle.putParcelable(ARG_READ_PROGRESS, progress)
            bundle.putParcelable(ARG_PAGER_SCROLL_STATE, scrollState)
            fragment.arguments = bundle

            return fragment
        }

        internal fun saveReadProgressBack(readProgress: ReadProgress) {
            val dbWrapper = ReadProgressBiz.instance
            dbWrapper.saveReadProgressAsync(readProgress)
        }

        private fun filterPostAfterBlacklistChanged(dataSet: List<Any>): List<Any> {
            LooperUtil.enforceOnWorkThread()
            return dataSet.filterIsInstance<Post>()
                .mapNotNull { Posts.filterPost(it, true) }
        }
    }
}
