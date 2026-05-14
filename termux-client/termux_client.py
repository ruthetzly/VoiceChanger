#!/usr/bin/env python3
"""
🐉 大龙变声翻译器 - Termux手机版
在手机上通过Termux运行，无需编译APK

安装方法：
1. 安装 Termux (F-Droid版本)
2. pkg install python
3. pip install requests pyaudio
4. python termux_client.py

配合Chatous使用：
- 开启扬声器模式后，变声后的声音会外放
- Chatous等APP的麦克风会自然拾取处理后的声音
"""

import requests
import json
import base64
import sys
import os
import tempfile
import struct
import time

# 服务器地址（大龙的云端服务器）
SERVER_URL = "http://101.37.237.237:8765"

# ========== 音频处理 ==========

SAMPLE_RATE = 16000
CHUNK_SIZE = 2048
FORMAT = 8  # 16-bit int
CHANNELS = 1

try:
    import pyaudio as pa
    HAS_AUDIO = True
except ImportError:
    HAS_AUDIO = False
    print("⚠️ 未安装pyaudio，请运行: pip install pyaudio")

try:
    import pydub
    from pydub import AudioSegment
    from pydub.playback import play
    HAS_PYDUB = True
except ImportError:
    HAS_PYDUB = False


def pcm_to_wav(pcm_data):
    """PCM原始数据转WAV格式"""
    data_size = len(pcm_data)
    file_size = 36 + data_size
    
    wav = bytearray()
    # RIFF header
    wav.extend(b'RIFF')
    wav.extend(struct.pack('<I', file_size))
    wav.extend(b'WAVE')
    wav.extend(b'fmt ')
    wav.extend(struct.pack('<I', 16))       # Subchunk1Size
    wav.extend(struct.pack('<H', 1))         # AudioFormat (PCM)
    wav.extend(struct.pack('<H', CHANNELS))  # NumChannels
    wav.extend(struct.pack('<I', SAMPLE_RATE))
    wav.extend(struct.pack('<I', SAMPLE_RATE * 2))  # ByteRate
    wav.extend(struct.pack('<H', 2))         # BlockAlign
    wav.extend(struct.pack('<H', 16))        # BitsPerSample
    wav.extend(b'data')
    wav.extend(struct.pack('<I', data_size))
    wav.extend(pcm_data)
    return bytes(wav)


def play_audio(mp3_bytes):
    """播放处理后的MP3音频"""
    if HAS_PYDUB:
        try:
            with tempfile.NamedTemporaryFile(suffix='.mp3', delete=False) as f:
                f.write(mp3_bytes)
                tmp_path = f.name
            audio = AudioSegment.from_mp3(tmp_path)
            play(audio)
            os.unlink(tmp_path)
            return True
        except Exception as e:
            print(f"  播放失败: {e}")
    
    # 降级方案：保存到文件
    with tempfile.NamedTemporaryFile(suffix='.mp3', delete=False) as f:
        f.write(mp3_bytes)
        print(f"  🔊 音频已保存: {f.name}")
        print(f"  请用音乐播放器打开收听")
    return False


def record_and_process():
    """录音→发送→接收→播放 循环"""
    if not HAS_AUDIO:
        print("❌ 需要安装pyaudio才能录音")
        return
    
    audio = pa.PyAudio()
    
    # 打开录音流
    stream = audio.open(
        format=FORMAT,
        channels=CHANNELS,
        rate=SAMPLE_RATE,
        input=True,
        frames_per_buffer=CHUNK_SIZE
    )
    
    print("\n🎤 录音中... (按 Ctrl+C 停止)")
    print("📢 扬声器模式已开启，可配合Chatous使用")
    
    audio_buffer = bytearray()
    
    try:
        while True:
            data = stream.read(CHUNK_SIZE, exception_on_overflow=False)
            audio_buffer.extend(data)
            
            # 每2秒发送一次
            if len(audio_buffer) >= SAMPLE_RATE * 2 * 2:  # 2秒 × 16bit
                send_audio(bytes(audio_buffer))
                audio_buffer = bytearray()
                
    except KeyboardInterrupt:
        print("\n⏹ 已停止")
    
    stream.stop_stream()
    stream.close()
    audio.terminate()


def send_audio(pcm_data):
    """发送音频到服务器并处理响应"""
    try:
        # PCM转WAV
        wav_data = pcm_to_wav(pcm_data)
        
        # 发送请求
        files = {'audio': ('audio.wav', wav_data, 'audio/wav')}
        resp = requests.post(f"{SERVER_URL}/api/process", files=files, timeout=30)
        
        if resp.status_code == 200:
            result = resp.json()
            if result.get('status') == 'ok':
                print(f"\n🔊 识别(中文): {result.get('original_text', '')}")
                print(f"🌏 翻译(印尼语): {result.get('translated_text', '')}")
                print(f"👩 声音: {result.get('voice', '女声')}")
                
                # 播放女声音频
                audio_b64 = result.get('audio_base64')
                if audio_b64:
                    audio_bytes = base64.b64decode(audio_b64)
                    play_audio(audio_bytes)
            else:
                print(f"❌ 处理错误: {result.get('error', '未知错误')}")
        else:
            print(f"❌ 网络错误: {resp.status_code}")
            
    except requests.exceptions.Timeout:
        print("⏱ 请求超时")
    except requests.exceptions.ConnectionError:
        print(f"🔴 无法连接服务器 {SERVER_URL}")
    except Exception as e:
        print(f"❌ 错误: {e}")


def test_server():
    """测试服务器连接"""
    try:
        resp = requests.get(f"{SERVER_URL}/api/info", timeout=5)
        if resp.status_code == 200:
            info = resp.json()
            print(f"✅ 服务器连接成功!")
            print(f"   应用: {info.get('app', '')}")
            print(f"   流程: {info.get('pipeline', '')}")
            voices = info.get('available_voices', [])
            for v in voices:
                print(f"   可用声音: {v.get('name')} ({v.get('gender')})")
            return True
    except Exception as e:
        print(f"❌ 无法连接服务器: {e}")
    return False


def show_menu():
    """交互菜单"""
    while True:
        print("\n" + "=" * 50)
        print("🐉 大龙变声翻译器 - Termux版")
        print("=" * 50)
        print(f"服务器: {SERVER_URL}")
        print("=" * 50)
        print("1. 🎤 开始变声（男→女+翻译印尼语）")
        print("2. 🔍 测试服务器连接")
        print("3. ⚙ 修改服务器地址")
        print("4. ❓ 使用说明")
        print("5. 🚪 退出")
        print("=" * 50)
        
        choice = input("请选择 [1-5]: ").strip()
        
        if choice == '1':
            if test_server():
                record_and_process()
        elif choice == '2':
            test_server()
        elif choice == '3':
            global SERVER_URL
            new_url = input(f"输入服务器地址 (当前: {SERVER_URL}): ").strip()
            if new_url:
                SERVER_URL = new_url
                print(f"✅ 已更新: {SERVER_URL}")
        elif choice == '4':
            show_help()
        elif choice == '5':
            print("👋 再见!")
            break


def show_help():
    print("""
📖 使用说明
═══════════════════════════════
🎯 功能:
  中文男声 → 语音识别 → 翻译印尼语 → 女声输出

📱 配合Chatous等视频聊天APP使用:
  1. 运行本脚本，选择"开始变声"
  2. 说话（中文），服务器处理后通过扬声器外放
  3. 打开Chatous，对方听到的将是印尼语女声

📦 依赖安装:
  pkg install python
  pip install requests pyaudio pydub

🌐 服务器:
  默认连接大龙的云端服务器
  也可修改为私有服务器地址
═══════════════════════════════
""")


if __name__ == "__main__":
    print("🐉 大龙变声翻译器 v1.0")
    print("=" * 50)
    
    if not HAS_AUDIO:
        print("⚠️ 注意: 未安装pyaudio，录音功能不可用")
        print("  请运行: pip install pyaudio")
        print("  或在Termux中: pkg install python && pip install pyaudio")
    
    test_server()
    show_menu()
