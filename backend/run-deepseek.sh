#!/usr/bin/env bash
# 用 DeepSeek（OpenAI 兼容）运行 ScriptForge 后端。
# Key 来源（任选其一，均不入库）：环境变量 DEEPSEEK_API_KEY，或 backend/.deepseek.key 文件。
set -e
here="$(cd "$(dirname "$0")" && pwd)"

key="${DEEPSEEK_API_KEY:-}"
if [ -z "$key" ] && [ -f "$here/.deepseek.key" ]; then
  key="$(tr -d '\r\n' < "$here/.deepseek.key")"
fi
if [ -z "$key" ]; then
  echo "未找到 DeepSeek API Key：设置 DEEPSEEK_API_KEY，或在 backend/.deepseek.key 写入 key（单行）。" >&2
  exit 1
fi

export SCRIPTFORGE_LLM_PROVIDER=openai
export SCRIPTFORGE_LLM_BASE_URL=https://api.deepseek.com/v1
export SCRIPTFORGE_LLM_MODEL=deepseek-chat
export SCRIPTFORGE_LLM_API_KEY="$key"

jar="$here/target/novel-to-screenplay.jar"
if [ ! -f "$jar" ]; then
  echo "未找到 jar：请先在 backend/ 打包：JAVA_HOME=D:/JDK/jdk17 mvn -DskipTests package" >&2
  exit 1
fi
echo "→ 以 DeepSeek (deepseek-chat) 启动后端 :8080 ..."
exec java -Dfile.encoding=UTF-8 -jar "$jar"
