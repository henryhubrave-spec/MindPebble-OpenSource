# 开源发布流程

## 发布边界

此版本为手机 0.5.4 / 手表 0.4.5 源码预览，原创代码 MIT。公开仓库使用干净快照初始化，原开发仓库继续私有保存，避免个人内容经历史提交或内部引用公开。后续维护直接在公开仓库提交经过审查的改动。

不包含个人录音、数据库、原始日志、设备升级记录、签名私钥、环境配置、个人 AGENTS.md、旧 Git 历史、原始设计稿及来源未明确的图片。第三方许可证保持原文，不能因为包含原作者邮箱而删除署名。

## 1. 检查与构建

按 [BUILD](BUILD.md) 配置 JDK 和 Android 工具链，获取固定哈希依赖，构建手机与手表并核对签名。执行 Python 契约、音频保留、Agent 评估测试，以及 `git diff --check`。Android 测试仅限一次性模拟器。历史测试与本次实际执行结果分别见 [VALIDATION](VALIDATION.md) 和 [RELEASE_VALIDATION](RELEASE_VALIDATION.md)。

检查 README、手表部署教程、隐私说明、第三方许可及宣传文字是否与版本一致。公开版首发不分发个人开发签名 APK，也不上传旧安装包、测试包或本地 outputs 目录。

## 2. 生成白名单快照

```sh
python3 tools/prepare-open-source.py --dest outputs/open-source/MindPebble-source
```

目标目录必须不存在；脚本遇到未审查类型、软链接、个人路径或常见凭据即失败。输出源码目录、相邻 ZIP、ZIP SHA-256 和源码内逐文件 `SOURCE_MANIFEST.json`。人工复核清单及全部图片。该静态检查不是完整安全审计。

## 3. 初始化全新历史并发布

在 GitHub 建立全新空的 **Public** 仓库，不要把原私有仓库直接改为公开，也不要 mirror 推送。在生成的快照目录执行：

```sh
cd outputs/open-source/MindPebble-source
git init -b main
git add .
git -c user.name='MindPebble contributors' -c user.email='contributors@users.noreply.github.com' commit -m 'Publish sanitized MindPebble source preview'
git remote add origin https://github.com/henryhubrave-spec/MindPebble-OpenSource.git
git push -u origin main
```

仅对全新仓库使用以上初始化；后续禁止覆盖已有分支。首次公开后核对默认分支、MIT 展示、文档与图片链接、Actions 结果，以及匿名访问。CI 失败先排查，不宣传为已通过。

## 4. 版本与后续分发

首发是源码快照，不假称稳定版。未来发布 APK 时建立专用发布签名、明确密钥备份和升级路线，核对依赖及模型再分发条款，附 APK / 签名摘要、变更和限制。不要公开私钥，不用 debug 密码当正式密钥。签名不同不能直接覆盖已有个人安装。

## 5. 发布核验

确认默认分支、源码附件和校验值一致。宣传材料仅保存在维护者本地，不随源码分发。
