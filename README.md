# 🐉 大龙变声翻译器 - 完整项目

**中文男声 → 语音识别 → 翻译印尼语 → 女声输出**

---

## 项目结构

```
voice-changer/
├── server.py                  # 服务器端（运行在云端）
├── android/                   # Android Studio项目
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   ├── gradlew
│   └── app/
│       └── src/main/
│           ├── AndroidManifest.xml
│           ├── java/com/dalong/voicechanger/
│           │   ├── MainActivity.kt        # 主界面
│           │   └── AudioProcessingService.kt  # 核心服务
│           └── res/
└── termux-client/
    └── termux_client.py       # Termux手机版客户端
```

## 两种使用方式

### 方式一：Android Studio 编译APP（推荐）

1. 用 Android Studio 打开 `android/` 目录
2. Gradle自动下载依赖
3. 连接手机，点击运行 → APK自动安装

### 方式二：Termux 直接运行（无需编译）

在手机上安装 Termux (从 F-Droid 下载)：

```bash
pkg install python python-pip
pip install pyaudio pydub requests
cd termux-client/
python termux_client.py
```

## 配合Chatous等视频APP使用

1. 开启APP或Termux的**扬声器模式**（外放）
2. 点击「开始变声」
3. 打开Chatous进行视频通话
4. 你说中文 → 对方听到的就是**印尼语女声**
5. 原理：APP外放处理后的声音，Chatous的麦克风自然拾取

## 服务器地址

默认连接大龙的云端服务器: `101.37.237.237:8765`

## 技术原理

```
麦克风录入中文男声
    ↓
Google Speech Recognition (ASR)
    ↓ 中文文本
Googletrans (翻译)
    ↓ 印尼语文本
Edge TTS (Microsoft Neural Voice)
    ↓ 印尼语女声MP3
手机端播放 → 配合视频通话APP使用
```
