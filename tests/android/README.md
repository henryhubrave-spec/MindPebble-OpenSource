# Android 合成集成测试

测试只能在新建、可丢弃的模拟器执行。会导入和删除合成数据，禁止对个人手机或手表运行。构建环境见 [BUILD](../../docs/BUILD.md)。

```sh
bash tests/android/build.sh phone
bash tests/android/build.sh watch
python3 tests/android/run-regressions.py --serial emulator-5554 --avd ci-disposable --install
```

运行器会核对 emulator 序列号、ranchu/goldfish、AVD 名和开机状态；安装只允许目标包未安装的空模拟器。当前运行器执行手机专项；手表专项须在独立模拟器使用对应 instrumentation phase。CI 配置见 `.github/workflows/android.yml`。

模拟器不证明真实蓝牙射频、厂商固件、手表麦克风、长时间后台与续航。历史测试结果见 [VALIDATION](../../docs/VALIDATION.md)。
