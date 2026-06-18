# WeChatExporter APK - 微信聊天记录导出工具

Copyright © 泪无痕

## 功能介绍

- Root权限读取微信数据库
- 导出.db文件到手机存储
- 需要Root权限

## 使用说明

1. 手机需要Root权限
2. 安装APK到手机
3. 打开应用，授予Root权限
4. 点击"导出数据库"
5. 导出的文件保存在 `/sdcard/WeChatExporter/`

## 安装方式

### USB安装（推荐）
```bash
adb install WeChatExporter.apk
```

### 注意事项
- 微信文件传输会损坏APK，必须用USB/ADB安装
- 需要Android 7.0+

## 文件说明

```
WeChatExporter_APK/
├── WeChatExporter.apk          # 安装包
├── 源代码/
│   ├── AndroidManifest.xml     # 应用配置
│   ├── src/                    # Java源代码
│   └── res/                    # 资源文件
└── README.md                   # 说明文档
```
