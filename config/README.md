# 本地配置示例

ADR0002采用双端架构。provider-profiles.example.json v2说明手机本地Agent与分别配置的ASR/LLM；没有默认供应商、个人地址或密钥，不依赖服务器。该JSON是文档，不由当前APK直接读取。

手机原型实际使用SecureSettings保存设置：VAD默认false，API地址/模型保存在应用私有配置，API Key使用Android Keystore AES-GCM加密。默认不备份到云。当前 APK 具有网络权限用于用户启动的离线资源下载与已确认的模型请求；保存 API 配置不测试连接。

未来AI请求只在用户明确任务或另行启用的限定自动规则中进行；未配置也能导入和播放。ASR与LLM不能仅凭地址相同就视为能力相同，普通LLM接口未必支持音频转写。

capture-policy.example.json描述候选VAD和传感器参数，不代表实际引擎已接入或策略已在手表生效。默认关闭VAD，完整保存；手表生效需能力协商与对应策略修订ACK。公开配置只包含空Key，实际Key不写JSON导出。
