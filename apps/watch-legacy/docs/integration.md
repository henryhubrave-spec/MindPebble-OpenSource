# 后续交接约定（设计预留，未实现网络接口）

仅将配对成功的 `.m4a` + 同名 `.m4a.json` 交给后续消费者；忽略 `.part` 和 `.tmp`。音频成功保存先于JSON写入，所以扫描器应重试缺JSON的完整音频，异常时人工处理，不能假设两个文件同时出现。

JSON schema_version=1：

| 字段 | 意义 |
|---|---|
| session_id | 每次开始生成UUID，暂停/继续不变，停止后重新开始更新 |
| sequence | 会话内递增序号；异常可能产生缺号 |
| audio_file | 相对当前目录的音频文件名 |
| started_at_epoch_ms / ended_at_epoch_ms | 墙上时钟的Unix毫秒，可被设备校时影响 |
| capture_elapsed_ms | 从录音器start返回到stop调用前的单调时钟时长，非媒体解析后的精确时长 |
| requested_codec / requested_sample_rate_hz / requested_bit_rate / channels | 申请的编码配置；以实际解析音频为准 |
| reason | segment / pause / stop / error / service_destroyed |
| bytes | 保存后的音频大小 |
| sync_state | local_only；本版始终保持此值 |

手机同步阶段建议以 `(session_id, sequence)` 加内容SHA256做幂等校验，验证远端文件长度及摘要，得到持久化确认后才允许用户选择删除手表副本。为完成文件生成独立上传队列；保留原始音频，禁止边录边删。不得直接开放无认证的局域网上传端口。

转写服务适配器接收音频、会话ID、开始时间与时区，返回独立 transcript.json；记录实际模型、语言、分段时间与错误状态，重试不覆盖原始文件。摘要和长期记忆必须支持回溯到原录音时间段并允许删除。

Speakr接入阶段需核对届时使用版本的真实导入/API/认证能力，再实现适配器；这里没有虚构任何Speakr端点。用户确认服务地址、数据去向及授权前，不上传、不启用长期记忆或Agent执行。

本版采用MediaRecorder；未来若要求无缝分段，应评估AudioRecord + MediaCodec + 时间戳/封装流水线，另行做API26实机、功耗与丢帧验证。
