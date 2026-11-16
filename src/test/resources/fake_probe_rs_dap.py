"""A scripted stand-in for `probe-rs dap-server`, speaking DAP over stdio.

It reproduces the probe-rs behavior the relay depends on: after `launch` it announces an RTT channel and only
streams RTT data once the client has answered with `rttWindowOpened`.
"""
import json
import sys

inp = sys.stdin.buffer
out = sys.stdout.buffer
seq = 0


def read():
    length = None
    while True:
        line = inp.readline()
        if not line:
            return None
        line = line.strip()
        if not line:
            if length is not None:
                break
            continue
        name, _, value = line.decode("ascii").partition(":")
        if name.strip().lower() == "content-length":
            length = int(value)
    return json.loads(inp.read(length))


def send(msg):
    global seq
    seq += 1
    msg["seq"] = seq
    body = json.dumps(msg).encode("utf-8")
    out.write(b"Content-Length: %d\r\n\r\n" % len(body) + body)
    out.flush()


def respond(req, body=None, success=True):
    send({"type": "response", "request_seq": req["seq"], "command": req["command"], "success": success, "body": body or {}})


def event(name, body):
    send({"type": "event", "event": name, "body": body})


while True:
    req = read()
    if req is None:
        break
    cmd = req.get("command")
    if cmd == "initialize":
        respond(req, {"supportsConfigurationDoneRequest": True, "supportsReadMemoryRequest": True})
        event("initialized", {})
    elif cmd == "launch":
        respond(req)
        event("probe-rs-rtt-channel-config", {"channelNumber": 0, "channelName": "defmt", "dataFormat": "Defmt"})
    elif cmd == "rttWindowOpened":
        if req["seq"] < 1_000_000 or not req["arguments"].get("windowIsOpen"):
            respond(req, success=False)
            continue
        respond(req)
        event("probe-rs-rtt-data", {"channelNumber": 0, "data": "0.000123 INFO  Hello from the board\n"})
        event("probe-rs-show-message", {"severity": "warning", "message": "Flash verify skipped"})
    elif cmd == "disconnect":
        respond(req)
        break
    else:
        respond(req)
