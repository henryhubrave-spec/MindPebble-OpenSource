# 离线VAD试听实验

这是可运行的本地工具，不是Android采集引擎。只处理用户明确提供/导出的音频；不联网、不改写/删除源文件，也不与手表连接。输出文件和真实时间映射放在仓库外。

## 使用

需要FFmpeg及独立虚拟环境，安装本目录requirements.txt。例子中的input是用户自己的文件，不能提交到仓库。

```sh
python preview.py /path/to/input.m4a --output /path/to/new-private-output-dir --mode 0
```

可以传入多段有明确顺序的文件；每段分别运行VAD，合并为一个精简试听文件。边界按每个源文件的PCM时间映射，不跨源文件伪造缺失声音。

输出speech-only-preview.m4a、time-map.json；目的目录必须不存在，避免覆盖已有内容。映射使用16kHz PCM样本序号，记录每一段输出对应哪段来源的何时。AAC编码后的采样填充需要播放器适配；本实验不声称精确到每个编码样本的交互跳转已实现。

模式0最宽松，依次到3更激进。当前固定500ms前置、800ms尾部和700ms合并保护，强调召回而不是最大裁剪率。必须对照原音频试听；噪声误判和人声漏判均可能发生。

## 参考区间逻辑

retention.py只将帧级检测结果转为合并区间，支持显式标记保护与不确定时保留。preview.py尚未接入真实用户标记导入，不能把纯区间测试当作该功能已经贯穿工具或Android端。无任何检测人声时不生成空AAC，仍输出结果报告。

```sh
python -m unittest discover -s tests -v
```

11项测试仅验证区间逻辑与边界。WebRTC模型召回、Android实时功耗和真实噪声场景另测。

## 第三方依赖

webrtcvad-wheels 2.0.14：Python包装器元数据标注MIT，包含WebRTC代码所附许可，使用/重新分发应保留对应声明。参考[维护仓库](https://github.com/daanzu/py-webrtcvad-wheels)和[原始接口说明](https://github.com/wiseman/py-webrtcvad)。本项目没有把依赖源码或二进制复制进仓库；此Python依赖仅供离线实验，不代表Android端已完成NDK集成。
