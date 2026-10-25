@echo off
cd /d F:\ChatGPT\argus-loom\backend
rem 2026-10-24 实测：原 corretto 路径不存在导致启动失败；改用当前可用 JDK21
set JAVA_HOME=F:\DevelopmentTools\Java\jdk-21
set PATH=%JAVA_HOME%\bin;%PATH%
for /f "usebackq eol=# tokens=*" %%a in ("F:\ChatGPT\argus-loom\.env") do set "%%a"
call mvnw.cmd -q spring-boot:run > F:\ChatGPT\argus-loom\logs\backend-detached.log 2>&1
