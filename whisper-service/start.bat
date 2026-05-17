@echo off
rem ─────────────────────────────────────────────────────────────────────────────
rem  Manual launcher — only needed if you set  whisper.auto_start: false
rem  in plugins/VoicechatModerator/config.yml.
rem
rem  When auto_start is true (default) the plugin manages this process for you.
rem ─────────────────────────────────────────────────────────────────────────────

set WHISPER_MODEL=tiny
set WHISPER_HOST=127.0.0.1
set WHISPER_PORT=8765

echo Starting Whisper service manually (model: %WHISPER_MODEL%)
echo.
python server.py
pause
