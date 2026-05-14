#!/usr/bin/env python3
"""
🐉 大龙变声翻译器 - 核心服务
中文男声 → 识别 → 翻译印尼语 → 女声输出

运行: python3 voice_changer_server.py
"""
import asyncio
import json
import base64
import wave
import io
import os
import tempfile
from http import HTTPStatus

# ===== 语音识别 =====
import speech_recognition as sr

# ===== 翻译 =====
from googletrans import Translator

# ===== TTS女声 =====
import edge_tts

# ===== Web服务器 =====
from aiohttp import web

# ===== 音频处理 =====
import numpy as np

recognizer = sr.Recognizer()
translator = Translator()

# 印尼语女声（Microsoft Edge TTS）
ID_FEMALE_VOICES = [
    "id-ID-GadisNeural",   # 印尼语女声
    "id-ID-ArdiNeural",    # 印尼语男声（不用）
]
ID_VOICE = "id-ID-GadisNeural"

async def process_audio(audio_data: bytes) -> dict:
    """处理音频主流程：ASR → 翻译 → TTS"""
    result = {"status": "ok"}
    
    try:
        # 1. 语音识别（中文）
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as f:
            f.write(audio_data)
            tmp_path = f.name
        
        with sr.AudioFile(tmp_path) as source:
            audio = recognizer.record(source)
        
        try:
            chinese_text = recognizer.recognize_google(audio, language="zh-CN")
            result["original_text"] = chinese_text
            result["original_lang"] = "zh-CN"
        except sr.UnknownValueError:
            result["error"] = "无法识别语音"
            os.unlink(tmp_path)
            return result
        except sr.RequestError:
            result["error"] = "语音识别服务不可用"
            os.unlink(tmp_path)
            return result
        
        os.unlink(tmp_path)
        
        # 2. 翻译中文→印尼语
        try:
            translated = await translator.translate(chinese_text, src="zh-cn", dest="id")
            indonesian_text = translated.text
            result["translated_text"] = indonesian_text
        except Exception as e:
            result["error"] = f"翻译失败: {str(e)}"
            return result
        
        # 3. TTS 印尼语女声
        try:
            tts = edge_tts.Communicate(indonesian_text, ID_VOICE)
            audio_bytes = b""
            async for chunk in tts.stream():
                if chunk["type"] == "audio":
                    audio_bytes += chunk["data"]
            
            # 返回base64编码的音频
            result["audio_base64"] = base64.b64encode(audio_bytes).decode("utf-8")
            result["voice"] = "印尼语女声"
        except Exception as e:
            result["error"] = f"语音合成失败: {str(e)}"
            return result
        
        return result
    
    except Exception as e:
        return {"status": "error", "error": str(e)}


# ===== Web API =====
routes = web.RouteTableDef()

@routes.get("/")
async def index(request):
    return web.json_response({
        "app": "大龙变声翻译器",
        "version": "1.0",
        "description": "中文男声 → 印尼语女声",
        "status": "running",
        "api": {
            "process": "POST /api/process (multipart with audio file)",
            "info": "GET /api/info"
        }
    })

@routes.get("/api/info")
async def info(request):
    """获取服务信息和可用声音"""
    voices = await edge_tts.list_voices()
    id_voices = [v for v in voices if v["Locale"] == "id-ID"]
    return web.json_response({
        "app": "大龙变声翻译器",
        "pipeline": "中文语音 → 文字 → 印尼语翻译 → 女声合成",
        "available_voices": [
            {"name": v["ShortName"], "gender": v["Gender"], "desc": f"{v['Locale']} - {v['Gender']}"}
            for v in id_voices
        ]
    })

@routes.post("/api/process")
async def process_audio_endpoint(request):
    """处理音频文件：上传WAV → 识别→翻译→TTS → 返回音频"""
    reader = await request.multipart()
    
    field = await reader.next()
    if not field or field.name != "audio":
        return web.json_response({"error": "需要上传 audio 字段的WAV文件"}, status=400)
    
    audio_data = await field.read()
    
    if len(audio_data) == 0:
        return web.json_response({"error": "音频数据为空"}, status=400)
    
    result = await process_audio(audio_data)
    
    if "error" in result:
        return web.json_response(result, status=500)
    
    return web.json_response(result)

@routes.post("/api/process_websocket")
async def ws_handler(request):
    """WebSocket实时处理"""
    ws = web.WebSocketResponse()
    await ws.prepare(request)
    
    async for msg in ws:
        if msg.type == web.WSMsgType.BINARY:
            # 处理接收到的音频
            result = await process_audio(msg.data)
            if "error" in result:
                await ws.send_json(result)
            else:
                await ws.send_json(result)
        elif msg.type == web.WSMsgType.TEXT:
            data = json.loads(msg.data)
            if data.get("action") == "ping":
                await ws.send_json({"action": "pong"})
    
    return ws

# ===== 启动 =====
async def main():
    app = web.Application()
    app.add_routes(routes)
    
    print("=" * 60)
    print("🐉 大龙变声翻译器 - 服务端")
    print("=" * 60)
    print(" 中文男声 → 印尼语女声")
    print(" API: http://0.0.0.0:8765")
    print(" WebSocket: ws://0.0.0.0:8765/api/process_websocket")
    print("=" * 60)
    
    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, "0.0.0.0", 8765)
    await site.start()
    
    # 保持运行
    await asyncio.Event().wait()

if __name__ == "__main__":
    asyncio.run(main())
