# AirStudy

AirStudy 是一款以飞行体验组织专注时间的 Android 应用。选择机场、准备航班，在飞行中计时，并将完成的行程保存在本地。

项目正在开发。已接入机场搜索、航线准备、座位选择、倒计时与正计时基础、暂停与恢复、降落机场选择、账号页面及本地存储。正计时结果页、记录与统计的完整衔接、再次起飞和手机适配仍需完成；当前版本尚未达到正式发布标准。

## 项目结构

| 目录 | 职责 |
|---|---|
| `AirStudy/app` | 页面导航、账号状态与飞行流程装配 |
| `AirStudy/feature` | 机场、准备、飞行、记录、统计、设置等页面 |
| `AirStudy/service-focus` | 飞行计时、暂停恢复、后台运行与结算 |
| `AirStudy/core` | 共享模型、网络、本地数据、偏好和界面组件 |
| `AirStudy/server` | 账号、机场查询、房间与日计划服务 |
| `AirStudy/widget` | 桌面小组件 |
| `tools` | 数据导入工具 |

详细说明见[运行与配置](docs/development.md)、[流程与数据](docs/architecture.md)和[待完成事项](docs/roadmap.md)。

## 本地构建

需要 JDK 17、Android SDK platform 37 和 build-tools 36.0.0。设置 `JAVA_HOME`、`ANDROID_HOME`，或按 `AirStudy/local.properties.example` 配置 SDK 路径。

```sh
cd AirStudy
# Windows 使用 gradlew.bat；其他系统使用 bash ./gradlew。
bash ./gradlew :app:assembleDebug
bash ./gradlew :core:core-model:test :service-focus:testDebugUnitTest :feature:feature-home:testDebugUnitTest
```

地图使用项目自己的配置，填写 `AirStudy/map.properties`，格式见同目录示例。配置缺失时界面明确显示不可用状态。

完整的服务端机场目录作为本地数据输入，通过 `tools/import_airports.py` 导入，详见运行说明。发布签名按 `AirStudy/keystore.properties.example` 配置；证书和密钥保留在本地。

## 协作

功能与缺陷通过 Issue 跟踪，修改通过独立分支和 Pull Request 审查。变更说明应列出可观察的行为变化、实际执行的检查和未完成范围。

本仓库代码许可见 [LICENSE](LICENSE)。第三方组件与数据分别适用其原有许可，见[第三方说明](docs/third-party.md)。
