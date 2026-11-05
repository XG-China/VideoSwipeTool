# 更新日志

## 1.0.42 (2026-11-05)

发布准备与工程化:

- `versionCode` 141 → 142,`versionName` 1.0.41 → 1.0.42
- 新增 Gradle wrapper(8.14),构建不再依赖本机预装的 Gradle
- 新增 release 签名配置:读取仓库外的 `keystore.properties`;未配置时沿用 debug 签名,
  保证可直接覆盖安装旧版本
- 新增 `.gitignore` 与 `keystore.properties.example`
- 修正 README 中不存在的脚本引用与构建/部署命令

功能逻辑与上一版一致,未改动广告判定规则。

## 1.0.41

上一稳定版本(基线)。
