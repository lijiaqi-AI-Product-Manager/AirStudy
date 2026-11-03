# 运行与配置

## Android

构建使用仓库中的 Gradle wrapper。JDK 为 17；SDK 需要 platform 37 和 build-tools 36.0.0。缓存完整时可追加 `--offline`。

SDK Manager 中的完整平台包标识为 `platforms;android-37.0`。

```sh
cd AirStudy
bash ./gradlew :app:assembleDebug
bash ./gradlew testDebugUnitTest :core:core-common:test :core:core-model:test
bash ./gradlew :app:lintDebug
```

`map.properties` 填写项目自己的 `apiKey` 和 `appId`。正式签名读取 `keystore.properties` 或 `PF_RELEASE_*` 环境变量。未配置签名时 Release 包为 unsigned。

## 机场目录

附近机场查询需要完整的 OurAirports CC0 数据快照。数据文件保留在 `data/`，不随源码提交。导入工具验证源文件哈希、83,798 条记录和输出哈希，拒绝使用截取或修改后的目录。

```sh
python tools/import_airports.py --input data/airports.csv
```

也可以导入已标准化的完整目录，工具接受两种格式并校验其固定哈希。要求的源文件 SHA-256 为 `190fa7298bfbbfe60ff49336ce6a2422f8c57754b615dab3ded67e9cf94081c0`；标准化输出为 `a29575efd8c744bccc834b15000d87cb015cfccc5d7c11784f3a547db091696f`。

新数据快照需要单独更新哈希与相应检查。缺少该输入时，全量附近机场查询与依赖它的服务端集成测试不可运行。

## 服务端

导入完整目录后，可在 `AirStudy` 下执行：

```sh
bash ./gradlew :server:test :server:checkContractPurity
bash ./gradlew :server:run
```

本地运行的数据库默认使用内存 H2。持久部署通过 `PF_DB_URL`、`PF_DB_USER`、`PF_DB_PASSWORD` 配置；认证通过 `PF_JWT_SECRET` 配置。生产环境要求至少 32 字节的密钥。端口读取 `PORT` 或 `PF_PORT`，默认 8080。

## 检查范围

持续集成运行共享模型、数据库升级、飞行服务、统计、记录卡片与列表、地图宿主组件、落地与准备衔接检查和应用构建。记录检查覆盖筛选边界、分页、两种模式的相同编号、实际图片分享、读取失败重试、账号切换与后台恢复；竖屏、横屏和夜间卡片另保留本地绘制结果。历史场景检查覆盖数据库关闭重开、相同编号的两种模式、原始归档保留、修订冲突、写入失败重试、旧版本升级以及选择器在后台和账号切换后的失效。记录删除检查覆盖实际数据库的同编号双模式、关联数据清理、旧确认冲突、账号切换、进行中与待结算保护、运行清理保护、保存失败回滚和重试；页面检查覆盖最后一页回退、汇总刷新和过期分享预览清除。完整服务端目录检查、手机适配和正式签名需要相应环境与输入，不能由编译结果代替。

额外质量任务包括 `checkNoHardcodedColors`、`:server:checkContractPurity`、`checkApkSize`。包体预算为 60 MiB；正式发布前须处理这些任务报告的问题。
