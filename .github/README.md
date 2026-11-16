# S1 Next Goose（tiger-git2022/s1-next）

<p align="center">
  <a href="https://github.com/tiger-git2022/s1-next/releases/latest"><img src="https://img.shields.io/github/v/release/tiger-git2022/s1-next?style=for-the-badge&label=%E4%B8%8B%E8%BD%BD%E6%9C%80%E6%96%B0%E7%89%88%20%2F%20Download&color=2ea44f" alt="下载最新版 / Download latest"></a>
</p>

**[⬇ 下载最新 APK / Download the latest APK](https://github.com/tiger-git2022/s1-next/releases/latest)**：`s1next-custom-v…apk`。

[中文](#中文) | [English](#english) | [上游说明 / Upstream README](../README.md)

## 中文

基于 [ykrank/S1-Next](https://github.com/ykrank/S1-Next)（Stage1st 论坛客户端「S1 Next 鹅版」）的个人分支，`custom` 分支在上游 `master` 的基础上增加：

- **论坛域名迁移到 stage1st.com**：`*.saraba1st.com` 的子域名已全部从 DNS 删除，原版所有请求都失败，列表一直显示「数据来自缓存」，投票、小黑屋、黑魔法也用不了。接口、头像、静态资源、App 接口都改用 `stage1st.com` 下的子域名；帖子里的旧链接会自动改写到新域名。
- **自动施放黑魔法**：楼层作者被禁止时，原版显示「作者被禁止或删除 内容自动屏蔽」，要手动点「点击施放黑魔法」。现在登录了 App 接口就自动拉取原文显示（按 pid 匹配，App 接口分页不同时按楼层号补拉）。
- **修复帖子末尾闪退**：在最后一页滑到底时，滚动回调可能发生在列表布局过程中，插入底部「加载中」项会崩溃（`Cannot call this method while RecyclerView is computing a layout or scrolling`）。
- **修复阅读进度偶尔不准**：原版只要还在加载（先显示缓存、网络还没返回，或上拉加载更多），退出时就不保存进度，下次回到更早的位置。
- **修复 App 接口登录失败**：R8 混淆把 OkHttp 的 `PublicSuffixDatabase.gz` 挪了位置，处理 Cookie 时报错。

`master` 分支与上游保持一致，不在上面开发。

### 下载

<https://github.com/tiger-git2022/s1-next/releases/latest>

包名 `me.ykrank.s1next.custom`，应用名和图标都与官方版相同（「S1 Next Goose」），可以和官方版（`me.ykrank.s1next`）同时安装，在系统「应用信息」里看包名区分。两者数据不互通：第一次需要重新登录；浏览记录可以在旧版「设置 → 备份 → 备份」导出，再在自用版「还原」。

### 版本号

和 [nga-client](https://github.com/tiger-git2022/nga-client)、[mihon](https://github.com/tiger-git2022/mihon) 一样用自己的版本号，不跟上游的版本号：

| | 规则 | 例子 |
|---|---|---|
| tag / versionName | `vX.Y.Z` | `v1.0.3` |
| versionCode | `X × 10000000 + Y × 10000 + Z` | `10000003` |

`.github/fork-version` 里写 `X.Y`：加了新功能（或合入上游的大更新）的 PR 把 Y 加 1，大改动把 X 加 1；Z 是这个 X.Y 的第几次发布，从 0 开始自动递增，修 bug 不用改文件。
上游版本号（`app/build.gradle.kts` 的 `appVersionName`、`appVersionCode`）不要改，会写在每个 Release 的说明里。

### GitHub Actions

| 工作流 | 触发 | 作用 |
|---|---|---|
| `tiger-ci.yml` | 合并到 `custom` 的 PR、手动 | 编译 custom 版（debug 签名），上传 APK |
| `tiger-release.yml` | 推送到 `custom`、手动、被同步工作流调用 | 编译签名 APK，发布到 Releases，并上传 `update.json`。同一个提交只发布一次（手动运行可勾选 force） |
| `tiger-sync-upstream.yml` | 每 6 小时、手动 | 把上游 `master` 分支合并进 `custom`；无冲突且编译通过就推送并发布，否则开 Issue |

上游不是每个版本都发 GitHub Release，所以同步跟的是 `master` 分支，而不是 Release tag。同步时会自动丢弃上游对 `.github/workflows` 的改动。

需要的仓库 Secrets：

- `KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`：release 签名（和 nga-client 等几个仓库共用同一把密钥）。
- `SYNC_TOKEN`（可选）：有 Contents 和 Workflows 写权限的 fine-grained PAT。上游提交改过工作流文件时，GitHub 不允许用 `GITHUB_TOKEN` 推送；没有这个 secret 时，同步会失败并开 Issue 说明。

### 本地编译

需要 JDK 17（Android Studio 自带的 JBR 也可以）。

```sh
# debug 版（包名 me.ykrank.s1next.debug）
./gradlew :app:assembleNormalDebug
# 自用版，用自己的签名
./gradlew :app:assembleNormalCustom -Ps1SigningFile=/path/to/signing.properties
```

`signing.properties` 里写 `storeFile`、`storePassword`、`keyAlias`、`keyPassword`；不传 `-Ps1SigningFile` 时用 debug 签名。
仓库放在 OneDrive 等同步盘里时，同步程序会锁住 `build` 目录导致编译失败，可以用 Gradle init 脚本把 `layout.buildDirectory` 指到同步盘外面。

### 代码位置

改动尽量放在独立文件里，对上游文件的修改集中在下面这些地方（新改动请加 `Fork` 注释）：

- `app/src/main/java/me/ykrank/s1next/data/api/Api.kt`、`data/api/app/AppApi.java`、`widget/net/AppDns.kt`、`widget/glide/OkHttpNoAvatarInterceptor.kt`、`data/api/model/PostAttachMent.kt`、`AndroidManifest.xml`：论坛域名
- `app/src/main/java/me/ykrank/s1next/view/page/post/postlist/PostListPagerFragment.kt`：帖子末尾闪退、阅读进度、自动黑魔法
- `app/proguard-rules.pro`：保留 okhttp3 包名
- `app/build.gradle.kts`、`app/src/custom/`：自用版构建类型、版本号、签名；自用版图标（纯黑背景，修正上游前景图向右下偏 10/12 像素的问题，和图标包里官方版的样子一致）

## English

A personal fork of [ykrank/S1-Next](https://github.com/ykrank/S1-Next), a client for the Stage1st forum. The `custom` branch adds, on top of upstream `master`:

- **Moved to stage1st.com**: all `*.saraba1st.com` hosts are gone from DNS, so upstream fails every request and keeps showing cached data; polls, the dark room and "dark magic" were broken too. Old links in posts are rewritten to the new host.
- **Automatic dark magic**: posts by banned authors are filled in from the app API automatically when it is logged in.
- **Fixes** for a crash near the end of a thread, reading progress that was not saved while loading, and an app API login failure caused by R8 moving OkHttp's `PublicSuffixDatabase.gz`.

Package `me.ykrank.s1next.custom`, installs beside the official app. Versions are the fork's own `vX.Y.Z` (X.Y in `.github/fork-version`, Z counted by the release workflow), versionCode `X × 10000000 + Y × 10000 + Z`. Releases are built by `tiger-release.yml` on every push to `custom`; `tiger-sync-upstream.yml` merges upstream `master` every 6 hours and releases when it builds, or opens an issue. Secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, optional `SYNC_TOKEN`. Build locally with JDK 17.
