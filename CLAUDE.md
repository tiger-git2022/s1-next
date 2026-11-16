# 给 Claude 的说明 / Notes for Claude

这是 Tiger 的 S1 Next（Stage1st 论坛客户端）个人分支，开发分支是 `custom`，`master` 和上游 [ykrank/S1-Next](https://github.com/ykrank/S1-Next) 保持一致。

- **用中文回复**仓库作者。
- **提交和 PR 标题**写成「中文 / English」，PR 正文中文在前；PR 合进 `custom`。
- **自动合并**：自己开的 PR，只要 CI（Tiger CI）通过、没有冲突、审查意见都已处理，就直接合并，不用再问。用 squash 合并，标题保留「中文 / English (#编号)」。只有需要作者拍板的设计问题，或者不打算按审查意见修改的，才先问再合并。
- **合并后检查发布**：推到 `custom` 会由 `.github/workflows/tiger-release.yml` 发布新版本（`vX.Y.Z`，X.Y 在 `.github/fork-version`，新功能的 PR 把 Y 加 1）。合并后确认这次发布成功；失败就修好，再开 PR、合并。
- 改动尽量放在独立文件里，必须改上游文件时集中改、标 `Fork` 注释，方便合并上游（见 `.github/README.md`）。
- 本地编译用 JDK 17（Android Studio 的 JBR 也行）。仓库在 OneDrive 里，同步会锁住 `build` 目录，编译时用 Gradle init 脚本（`-I`）把 `layout.buildDirectory` 放到 OneDrive 外面。
- 手机测试：自用版包名 `me.ykrank.s1next.custom`（和官方版并存），用 `-Ps1SigningFile` 指向本机 release 签名编译，同签名覆盖安装不丢登录和浏览记录。vivo 手机 adb 安装会弹「继续安装」确认。
