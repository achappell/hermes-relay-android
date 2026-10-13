#!/usr/bin/env python3
"""Probe Standard without printing tokens, session references, transcripts, or audio.

Requires Python 3.11+ and websockets. The token file is read locally on the host
running the probe. Use a dedicated test session; this submits two harmless prompts.
"""
import argparse
import asyncio
import json
from pathlib import Path
from urllib.parse import urlencode, urlsplit, urlunsplit

import websockets


def emit(**facts):
    print(json.dumps(facts), flush=True)


def authenticated_url(endpoint, token, audio=False, profile="default"):
    parsed = urlsplit(endpoint)
    if parsed.scheme not in ("https", "wss") or not parsed.hostname or parsed.username or parsed.query or parsed.fragment:
        raise ValueError("Use a secure endpoint without credentials, query, or fragment")
    path = parsed.path.rstrip("/")
    if not path.endswith("/api/ws"):
        raise ValueError("Use the Standard /api/ws endpoint")
    if audio:
        path = path[:-len("/ws")] + "/audio/speak-stream"
    query = {"token": token}
    if audio:
        query["profile"] = profile
    return urlunsplit(("wss", parsed.netloc, path, urlencode(query), ""))


async def rpc(socket, number, method, params, events):
    await socket.send(json.dumps({"jsonrpc": "2.0", "id": number, "method": method, "params": params}))
    while True:
        frame = json.loads(await socket.recv())
        if frame.get("id") == number:
            if "error" in frame:
                raise RuntimeError("Standard rejected the operation")
            return frame["result"]
        events.append(frame.get("params", {}).get("type"))


async def probe(args):
    token = Path(args.token_file).expanduser().read_text().strip()
    if not token:
        raise ValueError("Empty credential file")
    url = authenticated_url(args.endpoint, token)
    events = []
    context = {"source": "android", "profile": args.profile}
    async with asyncio.timeout(120):
        async with websockets.connect(url) as socket:
            session = await rpc(socket, 1, "session.create", context, events)
            runtime = session["session_id"]
            durable = session["stored_session_id"]
            emit(session_create=True, durable_reference=True)
            await rpc(socket, 2, "prompt.submit", {
                "session_id": runtime, "surface": "android",
                "text": "Reply with exactly ANDROID_BASELINE_OK. Do not use tools.",
            }, events)
            while True:
                event = json.loads(await socket.recv()).get("params", {})
                events.append(event.get("type"))
                if event.get("type") == "message.complete":
                    break
            emit(typed_completed=True, event_types=list(dict.fromkeys(events)), structured_prompt="not_reproduced")
        async with websockets.connect(url) as socket:
            resumed = await rpc(socket, 3, "session.resume", {**context, "session_id": durable}, events)
            emit(resume_succeeded=True, returned_reference_present=bool(resumed.get("stored_session_id")),
                 durable_reference_preserved=(resumed.get("stored_session_id") or durable) == durable,
                 history_continuity="ANDROID_BASELINE_OK" in json.dumps(resumed.get("messages", [])),
                 reconnect_prompt_count=0)
            await rpc(socket, 4, "prompt.submit", {
                "session_id": resumed["session_id"], "surface": "android",
                "text": "Write a long numbered explanation of the integers one through one hundred. Do not use tools.",
            }, events)
            while True:
                event = json.loads(await socket.recv()).get("params", {})
                if event.get("type") in ("thinking.delta", "message.delta"):
                    break
                if event.get("type") == "message.complete":
                    raise RuntimeError("Turn finished before interruption could be exercised")
            result = await rpc(socket, 5, "session.interrupt", {"session_id": resumed["session_id"]}, events)
            if result.get("status") != "interrupted":
                raise RuntimeError("Interrupt was not acknowledged")
            while True:
                event = json.loads(await socket.recv()).get("params", {})
                if event.get("type") == "message.complete":
                    status = event.get("payload", {}).get("status")
                    emit(interrupt_acknowledged=True, interrupt_terminal=True,
                         terminal_status=status if status in ("interrupted", "completed", "cancelled") else "other")
                    break
        audio_url = authenticated_url(args.endpoint, token, audio=True, profile=args.profile)
        async with websockets.connect(audio_url) as socket:
            await socket.send(json.dumps({"text": "Android Standard baseline audio check."}))
            await socket.send(json.dumps({"done": True}))
            count = size = 0
            started = False
            while True:
                frame = await socket.recv()
                if isinstance(frame, bytes):
                    if not started:
                        raise RuntimeError("Audio arrived before its format")
                    count += 1
                    size += len(frame)
                    continue
                event = json.loads(frame)
                if event.get("type") == "start":
                    started = True
                    emit(audio_sample_rate=event.get("sample_rate"), audio_channels=event.get("channels"))
                elif event.get("type") == "end":
                    if not size or size % 2:
                        raise RuntimeError("No complete PCM samples")
                    emit(audio_completed=True, binary_frames=count, audio_bytes=size)
                    break
                elif event.get("type") in ("fallback", "error"):
                    raise RuntimeError("Standard audio unavailable")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--endpoint", required=True)
    parser.add_argument("--token-file", required=True)
    parser.add_argument("--profile", default="default")
    args = parser.parse_args()
    try:
        asyncio.run(probe(args))
    except Exception as error:
        # Exception strings can include the authenticated URL. Never print them.
        emit(probe_failed=True, error_type=type(error).__name__)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
