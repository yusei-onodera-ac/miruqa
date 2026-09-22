"""OrcaRouter (https://www.orcarouter.ai/) 経由でエージェントを動かす検証用スクリプト。

agent/ 配下の Anthropic 直結版とは独立した最小実装。OrcaRouter は
OpenAI 互換の Chat Completions API なので、ツール呼び出しは
Anthropic の tool_runner ではなく OpenAI 形式の function calling で
手動ループを回す。

使い方:
    pip install openai
    export ORCAROUTER_API_KEY=...
    python orca_demo.py "workspace の中身を確認してreport.mdを作って"
"""

import argparse
import json
import os
from pathlib import Path

import time

from openai import OpenAI, RateLimitError

from agent.config import WORKSPACE_DIR

MODEL = os.environ.get("ORCA_MODEL", "orcarouter/auto")


def _resolve(path: str) -> Path:
    root = WORKSPACE_DIR.resolve()
    target = (root / path).resolve()
    if target != root and root not in target.parents:
        raise ValueError(f"'{path}' is outside the workspace directory.")
    return target


def read_file(path: str) -> str:
    target = _resolve(path)
    if not target.exists():
        return f"Error: {path} does not exist."
    return target.read_text(encoding="utf-8")


def write_file(path: str, content: str) -> str:
    target = _resolve(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")
    return f"Wrote {len(content)} chars to {path}"


def list_files(path: str = ".") -> str:
    target = _resolve(path)
    if not target.exists():
        return f"Error: {path} does not exist."
    entries = sorted(
        p.relative_to(WORKSPACE_DIR).as_posix() + ("/" if p.is_dir() else "")
        for p in target.iterdir()
    )
    return "\n".join(entries) if entries else "(empty)"


TOOL_IMPLS = {"read_file": read_file, "write_file": write_file, "list_files": list_files}

TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "read_file",
            "description": "workspace ディレクトリ内のテキストファイルを読む",
            "parameters": {
                "type": "object",
                "properties": {"path": {"type": "string", "description": "workspaceからの相対パス"}},
                "required": ["path"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "write_file",
            "description": "workspace ディレクトリ内にテキストファイルを作成・上書きする",
            "parameters": {
                "type": "object",
                "properties": {
                    "path": {"type": "string", "description": "workspaceからの相対パス"},
                    "content": {"type": "string", "description": "書き込む内容"},
                },
                "required": ["path", "content"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "list_files",
            "description": "workspace ディレクトリ内のファイル・フォルダ一覧を取得する",
            "parameters": {
                "type": "object",
                "properties": {"path": {"type": "string", "description": "workspaceからの相対パス。省略でルート"}},
                "required": [],
            },
        },
    },
]

SYSTEM_PROMPT = (
    "あなたは業務自動化エージェントです。与えられたツールを使い、"
    "workspace ディレクトリの中で作業してタスクを完了してください。"
)


def _client() -> OpenAI:
    # 既定は OrcaRouter。LLM_BASE_URL / LLM_API_KEY を指定すると、任意の
    # OpenAI互換エンドポイント(例: Ollama の http://localhost:11434/v1)に向けられる。
    return OpenAI(
        base_url=os.environ.get("LLM_BASE_URL", "https://api.orcarouter.ai/v1"),
        api_key=os.environ.get("LLM_API_KEY") or os.environ["ORCAROUTER_API_KEY"],
    )


def _create_with_retry(client, attempts: int = 8, wait_seconds: int = 20, **kwargs):
    """無料枠の一時的な混雑(err_free_rate)のときだけ、待って再試行する。"""
    for attempt in range(1, attempts + 1):
        try:
            return client.chat.completions.create(**kwargs)
        except RateLimitError as exc:
            body = exc.body if isinstance(exc.body, dict) else {}
            if (body.get("metadata") or {}).get("reason") != "err_free_rate" or attempt == attempts:
                raise
            print(f"  … 無料枠が混雑中のため{wait_seconds}秒待って再試行します({attempt}/{attempts - 1})", flush=True)
            time.sleep(wait_seconds)


def _compact_old_tool_results(messages: list, keep_recent: int) -> None:
    tool_indexes = [i for i, m in enumerate(messages) if m["role"] == "tool"]
    for i in tool_indexes[:-keep_recent] if keep_recent > 0 else tool_indexes:
        content = messages[i]["content"]
        if len(content) > 200:
            messages[i]["content"] = content[:200] + "…(古い結果のため省略)"


def run_loop(
    client, messages: list, tools: list, impls: dict, max_steps: int = 30, keep_recent_tool_results=None
) -> str:
    for _ in range(max_steps):
        if keep_recent_tool_results is not None:
            _compact_old_tool_results(messages, keep_recent_tool_results)
        response = _create_with_retry(client, model=MODEL, messages=messages, tools=tools)
        message = response.choices[0].message
        messages.append(message.model_dump(exclude_none=True))

        if not message.tool_calls:
            return message.content or ""

        for call in message.tool_calls:
            try:
                args = json.loads(call.function.arguments or "{}")
                result = impls[call.function.name](**args)
            except Exception as exc:
                result = f"Error: {exc!r}"
            messages.append({"role": "tool", "tool_call_id": call.id, "content": result})

    return f"(最大ステップ数 {max_steps} に達したため中断しました)"


def run_task(task: str) -> str:
    messages = [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": task},
    ]
    return run_loop(_client(), messages, TOOLS, TOOL_IMPLS)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="OrcaRouter経由でエージェントタスクを実行")
    parser.add_argument("task", nargs="?", help="実行したいタスクを自然言語で指定")
    parser.add_argument("--models", action="store_true", help="このAPIキーで使えるモデルID一覧を表示")
    args = parser.parse_args()

    if args.models:
        for model in _client().models.list():
            print(model.id)
    elif args.task:
        print(run_task(args.task))
    else:
        parser.error("task か --models のいずれかを指定してください")
