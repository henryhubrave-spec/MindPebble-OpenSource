# 数据契约草案与v1适配参考

三个JSON Schema定义片段、耐久回执和带来源分析结果，版本独立于App版本。它们是设计草案，不代表服务端/手机已实现协议。

`legacy_adapter.py`只把旧元数据和给定字节转换为新Manifest，保留源对象、计算SHA256并生成稳定segment_id。它不解析音频、不修改文件、不进行网络传输、不验证回执身份，也不能独自证明音频有效或允许删除手表副本。

同一session+sequence生成同一segment_id；相同ID不同SHA256必须由接收服务返回冲突，不能覆盖。时钟回拨保留墙上时间并标记，不改写单调时长。

Schema只检查结构、值域和格式。服务还必须检查：来源片段存在并属于该用户、来源起止顺序及是否在媒体长度内、模型输出引文是否匹配、回执来自认证接收端、确认级别是否满足清理策略。不能用JSON合法代替这些业务验收。

`examples`与测试PAYLOAD都是明确合成的数据；PAYLOAD不是有效音频。运行：

```sh
python3 -m pip install -r requirements-dev.txt
python3 -m unittest discover -s packages/contracts/tests -v
```

以上从仓库根目录执行。建议在虚拟环境安装依赖。
