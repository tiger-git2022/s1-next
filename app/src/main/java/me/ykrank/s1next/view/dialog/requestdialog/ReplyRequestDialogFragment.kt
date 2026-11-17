package me.ykrank.s1next.view.dialog.requestdialog

import android.os.Bundle
import android.text.TextUtils
import com.github.ykrank.androidtools.util.StringUtils
import io.reactivex.Single
import me.ykrank.s1next.App.Companion.get
import me.ykrank.s1next.R
import me.ykrank.s1next.data.api.Api
import me.ykrank.s1next.data.api.ApiException
import me.ykrank.s1next.data.api.model.Quote
import me.ykrank.s1next.data.api.model.wrapper.AccountResultWrapper
import me.ykrank.s1next.widget.track.event.NewReplyTrackEvent
import org.jsoup.Jsoup

/**
 * A dialog requests to reply to post.
 */
class ReplyRequestDialogFragment : BaseRequestDialogFragment<AccountResultWrapper>() {
    override fun getProgressMessage(): CharSequence? {
        return getText(R.string.dialog_progress_message_reply)
    }

    override fun getSourceObservable(): Single<AccountResultWrapper> {
        val threadId = requireArguments().getString(ARG_THREAD_ID)
        val quotePostId = requireArguments().getString(ARG_QUOTE_POST_ID)
        val reply = requireArguments().getString(ARG_REPLY)
        return if (TextUtils.isEmpty(quotePostId)) {
            flatMappedWithAuthenticityToken { s: String? -> mS1Service.reply(s, threadId, reply) }
        } else {
            mS1Service.getQuoteInfo(threadId, quotePostId).flatMap { s: String? ->
                // Fork：拿不到引用信息时（比如论坛要求实名、没有回复权限），显示论坛返回的原因，
                // 而不是「发生了一个未知的错误」
                val quote = Quote.fromXmlString(s)
                    ?: throw ajaxErrorMessage(s)?.let { ApiException.ApiServerException(it) }
                        ?: IllegalStateException("Cannot get the post information.")
                flatMappedWithAuthenticityToken { token: String? ->
                    mS1Service.replyQuote(
                        token, threadId, reply, quote.encodedUserId,
                        quote.quoteMessage, StringUtils.abbreviate(
                            reply,
                            Api.REPLY_NOTIFICATION_MAX_LENGTH
                        )
                    )
                }
            }
        }
    }

    override fun onNext(data: AccountResultWrapper) {
        val result = data.result
        if (result.defaultSuccess) {
            onRequestSuccess(result.message)
        } else {
            onRequestError(result.message)
        }
    }

    companion object {
        val TAG: String = ReplyRequestDialogFragment::class.java.getName()

        /**
         * Fork：Discuz 的 ajax 提示是 `<root><![CDATA[<div class="alert_error">原因<script>…</script></div>]]></root>`，
         * 取出其中的文字。
         */
        internal fun ajaxErrorMessage(xmlString: String?): String? {
            if (xmlString.isNullOrBlank()) return null
            val html = "<!\\[CDATA\\[(.*)]]>".toRegex(RegexOption.DOT_MATCHES_ALL)
                .find(xmlString)?.groupValues?.getOrNull(1) ?: xmlString
            val doc = Jsoup.parse(html)
            doc.select("script, style").remove()
            val text = doc.text().trim()
            return text.takeIf { it.isNotEmpty() && it.length <= 300 }
        }
        private const val ARG_THREAD_ID = "thread_id"
        private const val ARG_REPLY = "reply"
        private const val ARG_QUOTE_POST_ID = "quote_post_id"
        fun newInstance(
            threadId: String?, quotePostId: String?,
            reply: String?
        ): ReplyRequestDialogFragment {
            get().trackAgent.post(NewReplyTrackEvent(threadId, quotePostId))
            val fragment = ReplyRequestDialogFragment()
            val bundle = Bundle()
            bundle.putString(ARG_THREAD_ID, threadId)
            bundle.putString(ARG_QUOTE_POST_ID, quotePostId)
            bundle.putString(ARG_REPLY, reply)
            fragment.setArguments(bundle)
            return fragment
        }
    }
}
