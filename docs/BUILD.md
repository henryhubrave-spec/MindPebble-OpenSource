# 构建：手机 v0.5.4 / 手表 v0.4.5

统一脚本直接调用 Android 工具链，无 Gradle/GMS 依赖。需要 Bash、Python 3、JDK 17、zip、Android Build Tools 34.0.0，以及 API 26 和 API 34 官方平台。API 26 用于手表编译；手机 compile/target 34，双方 min 26。

```sh
export JAVA_HOME=/absolute/path/to/jdk17
export BUILD_TOOLS=/absolute/path/to/Android/sdk/build-tools/34.0.0
export ANDROID_JAR_26=/absolute/path/to/Android/sdk/platforms/android-26/android.jar
export ANDROID_JAR_34=/absolute/path/to/Android/sdk/platforms/android-34/android.jar
python3 tools/fetch-dependencies.py
bash apps/watch-legacy/build.sh
bash apps/phone/build.sh
```

`tools/dependencies.lock.json` 固定下载地址与 SHA-256：Android VAD WebRTC 2.0.10、Kotlin 2.2.0、R8 8.10.21、sherpa-onnx 1.13.8 AAR（与官方发布哈希一致）。首次获取需要联网；之后用缓存。下载依赖与运行 App 调用模型是两个独立行为。许可全文随源码及手机包分发。自托管镜像如替换依赖，应更新校验值并审计许可与兼容性。

构建后设置同一 JAVA_HOME/BUILD_TOOLS，运行 `python3 tools/check-apks.py` 可复核签名并生成 SHA256SUMS。

输出位于 `outputs/`；签名私钥分别位于 `work/watch-build/debug.jks` 与 `work/phone-build/debug.jks`，不入 Git。首次构建自动生成开发密钥；要覆盖已有安装必须保留对应密钥。生产分发前应建立独立发布签名流程，debug 密码不用于正式密钥。

## 本地检查

```sh
python3 -m venv work/contracts-venv
work/contracts-venv/bin/pip install -r requirements-dev.txt
work/contracts-venv/bin/python -m unittest discover -s packages/contracts/tests -v
python3 -m unittest discover -s experiments/speech-retention/tests -v
```

Android 集成测试见 `tests/android/README.md`，仅在一次性模拟器运行，会导入、删除和调整测试数据。不得对个人设备运行该测试包。标准 Android 14 模拟器不等价于 WATCH 2 Android 8 实机。

手表 0.4.4 构建时运行 `tools/fetch-watch-wakeword.py`，从模型作者 ModelScope 仓库取得固定提交的 INT8 中文唤醒模型；锁文件记录各文件 SHA-256，校验失败不打包。权重保存在忽略的 `work/watch-wakeword-assets/`，APK 含约 4.8 MiB 权重及 ARM32/ARM64 运行库；测试语音只放在 `work/watch-wakeword-tests/`，不进入 APK。设备上不联网下载、不上传音频。
