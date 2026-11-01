# ADR 0003：落地双端本地运行时

2026-10-18，已实施的 v0.2 原型。

手表保持 API 26 MediaRecorder，手机负责 SQLite、本地 VAD 与 Agent 队列。双端通信采用用户核对证书的局域网 TLS，不依赖 GMS；不添加服务器 Agent。为了旧设备与可审计性，保留 Java 原生 UI 和直接 Android 工具链构建。

首次 TLS 实现尝试直接 AndroidKeyStore RSA，模拟器 Conscrypt 握手失败。当前生成软件 RSA TLS 身份，用 Keystore AES-GCM 加密持久保存，加载至内存 KeyStore；握手已在两台模拟器间通过。此选择没有承诺所有 OEM TLS 实现已验证。

VAD 放手机，默认关闭，避免旧表增加实时算力负担。完整原音默认保留；明确释放后记录 SPEECH_ONLY，与完整耐久备份区分。传感器全部可选，缺测显式表达。

模型 API 仅通过 ProviderClient，兼容 OpenAI 风格 ASR/chat 请求，禁止重定向。数据来源快照与配置版本绑定确认，付费任务不自动重试。产出记忆和待办需用户采纳。
