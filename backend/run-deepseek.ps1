# Run ScriptForge backend with DeepSeek (OpenAI-compatible).
# API key source (either, never committed): env var DEEPSEEK_API_KEY, or backend/.deepseek.key file.
# ASCII-only on purpose: Windows PowerShell 5.1 mis-decodes UTF-8-without-BOM scripts.
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path

$key = $env:DEEPSEEK_API_KEY
if (-not $key -and (Test-Path "$here/.deepseek.key")) {
    $key = (Get-Content "$here/.deepseek.key" -Raw).Trim()
}
if (-not $key) {
    Write-Host "No DeepSeek API key found. Set `$env:DEEPSEEK_API_KEY or put it in backend/.deepseek.key (single line)." -ForegroundColor Red
    exit 1
}

$env:SCRIPTFORGE_LLM_PROVIDER = "openai"
$env:SCRIPTFORGE_LLM_BASE_URL = "https://api.deepseek.com/v1"
$env:SCRIPTFORGE_LLM_MODEL = "deepseek-chat"
$env:SCRIPTFORGE_LLM_API_KEY = $key

$jar = Join-Path $here "target/novel-to-screenplay.jar"
if (-not (Test-Path $jar)) {
    Write-Host "Jar not found. Build first in backend/:" -ForegroundColor Red
    Write-Host "  JAVA_HOME='D:/JDK/jdk17' 'D:/Maven/apache-maven-3.9.9/bin/mvn' -DskipTests package"
    exit 1
}
Write-Host "Starting backend :8080 with DeepSeek (deepseek-chat) ..." -ForegroundColor Yellow
& "D:/JDK/jdk17/bin/java" -Dfile.encoding=UTF-8 -jar $jar
