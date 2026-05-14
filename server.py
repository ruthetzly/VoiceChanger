#!/usr/bin/env python3
"""
🐉 大龙变声翻译器 v2 - 半离线版服务端
支持：
- POST /api/translate_tts: 翻译中文→印尼语 + TTS女声
- GET /audio/<file>: 提供音频文件下载
"""
import asyncio, json, os, uuid, sys
from aiohttp import web
from translate import Translator
import edge_tts

# 翻译器（支持重试）
def translate_text(text, src='zh', dest='id'):
    for attempt in range(3):
        try:
            t = Translator(from_lang=src, to_lang=dest)
            result = t.translate(text)
            if result and len(result) > 0:
                return result
        except:
            pass
        if attempt < 2:
            import time; time.sleep(1)
    return text  # 翻译失败返回原文

ID_VOICE = "id-ID-GadisNeural"
AUDIO_DIR = "/opt/openclaw/apps/voice-changer/audio_output"
os.makedirs(AUDIO_DIR, exist_ok=True)

routes = web.RouteTableDef()

@routes.get("/")
async def index(request):
    return web.json_response({
        "app": "大龙变声翻译器 v2",
        "status": "running",
        "pipeline": "Vosk离线识别 → 翻译印尼语 → 女声TTS"
    })

@routes.post("/api/translate_tts")
async def translate_tts(request):
    try:
        data = await request.post()
        text = data.get("text", "").strip()
        if not text:
            return web.json_response({"error": "text不能为空"}, status=400)
        
        # 1. 翻译
        translated_text = translate_text(text)
        
        # 2. TTS
        audio_filename = f"{uuid.uuid4()}.mp3"
        audio_path = os.path.join(AUDIO_DIR, audio_filename)
        tts = edge_tts.Communicate(translated_text, ID_VOICE)
        await tts.save(audio_path)
        
        audio_url = f"http://101.37.237.237:80/audio/{audio_filename}"
        asyncio.create_task(cleanup_audio(audio_path, 60))
        
        return web.json_response({
            "status": "ok",
            "original_text": text,
            "translated_text": translated_text,
            "audio_url": audio_url,
            "voice": "印尼语女声"
        })
    except Exception as e:
        return web.json_response({"status": "error", "error": str(e)}, status=500)

async def cleanup_audio(path, delay):
    await asyncio.sleep(delay)
    try: os.remove(path)
    except: pass

@routes.get("/audio/{filename}")
async def serve_audio(request):
    fp = os.path.join(AUDIO_DIR, request.match_info.get("filename", ""))
    if os.path.exists(fp):
        return web.FileResponse(fp)
    return web.json_response({"error": "文件不存在"}, status=404)

async def main():
    app = web.Application()
    app.add_routes(routes)
    print("🐉 大龙变声翻译器 v2 服务端")
    print("端口: 8765 (iptables 80 → 8765)")
    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, "0.0.0.0", 8765)
    await site.start()
    await asyncio.Event().wait()

if __name__ == "__main__":
    asyncio.run(main())
