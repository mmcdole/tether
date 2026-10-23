#!/usr/bin/env python3
"""A tiny MUD for testing Tether by hand. Python 3, stdlib only.

    python3 tools/testmud.py [--port 4000] [--mccp] [--password secret] [--chatter 5]

The emulator reaches this machine at 10.0.2.2. Commands: look, say <text>,
flood <n>, quit (closes cleanly), reset (closes with a TCP reset). Any name works; any password works unless --password is given.
"""
import argparse
import asyncio
import random
import re
import socket
import struct
import time
import zlib

IAC, DONT, DO, WONT, WILL, SB, GA, SE, EOR = 255, 254, 253, 252, 251, 250, 249, 240, 239
ECHO, OPT_EOR, NAWS, MCCP2 = 1, 25, 31, 86

ESC = "\x1b["
R = ESC + "0m"


def c(code, text):
    return f"{ESC}{code}m{text}{R}"


ROOM = "\r\n".join([
    c("1;33", "The Village Square"),
    c("37", "You are standing in the centre of the village. A fountain bubbles quietly here")
    + c("37", " and narrow roads lead off in every direction."),
    c("32", "    Obvious exits: north, south, east, west"),
    c("36", "A town guard (wandering).") + "  " + c("4;35", "underlined") + " " + c("3", "italic")
    + " " + c("2", "dim") + " " + c("7", "reverse") + " " + c("41;97", "white on red"),
    "16: " + "".join(c(30 + i, "#") for i in range(8)) + "".join(c(f"1;{30 + i}", "#") for i in range(8))
    + " " + "".join(c(90 + i, "#") for i in range(8)),
    "256: " + "".join(c(f"38;5;{n}", "█") for n in range(16, 232, 6)),
    "rgb: " + "".join(c(f"38;2;{255 - i * 8};{i * 8};200", "█") for i in range(32)),
]) + "\r\n"

PROMPT = c("92", "HP:131/160") + " " + c("94", "SP:88/90") + " " + c("90", ">") + " "

CHATTER = [
    c("35", "Hagbard tells you: ") + c("97", "wanna group for the troll?"),
    c("35", "Hagbard tells you: ") + c("97", "meet me at the north gate"),
    c("33", "The fountain gurgles."),
    c("36", "A small brown rat scurries past."),
    c("32", "Torvald shouts: ") + "the gate closes at midnight!",
]
COLORS = ["31", "32", "33", "34", "35", "36", "91", "92", "93", "94", "95", "96", "1;37"]


class Client:
    def __init__(self, reader, writer, args):
        self.reader, self.writer, self.args = reader, writer, args
        self.eor = False          # client agreed to EOR; otherwise prompts end with GA
        self.compress = None      # zlib stream once MCCP2 starts
        self.at_prompt = False
        self.peer = writer.get_extra_info("peername")

    def raw(self, data: bytes):
        if self.compress:
            data = self.compress.compress(data) + self.compress.flush(zlib.Z_SYNC_FLUSH)
        self.writer.write(data)

    def send(self, text: str):
        # Output arriving while the client sits at a prompt starts on a new line.
        if self.at_prompt and not text.startswith("\r\n"):
            text = "\r\n" + text
        self.at_prompt = False
        self.raw(text.replace("\xff", "\xff\xff").encode("utf-8"))

    def prompt(self, text=PROMPT):
        self.raw(text.encode("utf-8") + bytes([IAC, EOR if self.eor else GA]))
        self.at_prompt = True

    def telnet(self, *values):
        self.raw(bytes(values))

    async def read_line(self):
        """Reads one line of input, answering telnet negotiation on the way."""
        line = bytearray()
        while True:
            b = await self.reader.read(1)
            if not b:
                raise ConnectionError
            b = b[0]
            if b == IAC:
                cmd = (await self.reader.read(1))[0]
                if cmd in (WILL, WONT, DO, DONT):
                    await self.negotiated(cmd, (await self.reader.read(1))[0])
                elif cmd == SB:
                    sub = bytearray()
                    while not sub.endswith(bytes([IAC, SE])):
                        sub += await self.reader.read(1)
                    if sub[0] == NAWS and len(sub) >= 5:
                        print(f"{self.peer} NAWS {sub[1] * 256 + sub[2]}x{sub[3] * 256 + sub[4]}")
                continue
            if b == ord("\n"):
                self.at_prompt = False
                return line.decode("utf-8", "replace").rstrip("\r")
            line.append(b)

    async def negotiated(self, cmd, opt):
        if cmd == DO and opt == OPT_EOR:
            self.eor = True
        elif cmd == DO and opt == MCCP2 and self.args.mccp:
            self.telnet(IAC, SB, MCCP2, IAC, SE)
            self.compress = zlib.compressobj()
            print(f"{self.peer} MCCP2 on")

    async def login(self):
        self.send(c("1;36", "\r\n  T E S T M U D\r\n") + c("90", "  a place to try Tether\r\n\r\n"))
        self.prompt("By what name do you wish to be known? ")
        name = (await self.read_line()).strip() or "Stranger"
        while True:
            self.send("Password: ")
            self.telnet(IAC, WILL, ECHO)
            self.prompt("")
            password = await self.read_line()
            self.telnet(IAC, WONT, ECHO)
            self.raw(b"\r\n")
            self.at_prompt = False
            print(f"{self.peer} login {name!r}, password {len(password)} chars")
            if self.args.password is None or password == self.args.password:
                break
            self.send(c("31", "Wrong password.") + "\r\n")
        self.send(f"Welcome, {c('1;37', name.capitalize())}.\r\n\r\n" + ROOM)
        self.prompt()
        return name

    async def chatter(self):
        n = 0
        while True:
            await asyncio.sleep(self.args.chatter)
            n += 1
            line = random.choice(CHATTER)
            print(f"{self.peer} chatter {n} {time.strftime('%H:%M:%S')} {re.sub(r'\x1b\[[0-9;]*m', '', line)}")
            self.send(line + "\r\n")
            self.prompt()
            await self.writer.drain()

    async def run(self):
        print(f"{self.peer} connected")
        self.telnet(IAC, WILL, OPT_EOR, IAC, DO, NAWS)
        if self.args.mccp:
            self.telnet(IAC, WILL, MCCP2)
        ticker = None
        try:
            name = await self.login()
            ticker = asyncio.create_task(self.chatter())
            while True:
                await self.writer.drain()
                words = (await self.read_line()).strip().split(" ", 1)
                cmd, rest = words[0].lower(), words[1] if len(words) > 1 else ""
                if cmd == "look":
                    self.send(ROOM)
                elif cmd == "say":
                    self.send(c("1;33", f'You say: "{rest}"') + "\r\n")
                elif cmd == "flood":
                    n = int(rest) if rest.isdigit() else 100
                    for i in range(1, n + 1):
                        color = COLORS[i % len(COLORS)]
                        self.send(c(color, f"{i:5d} The {name} flood rolls on, line after colourful line.") + "\r\n")
                        if i % 200 == 0:
                            await self.writer.drain()
                elif cmd == "quit":
                    self.send("Goodbye.\r\n")
                    await self.writer.drain()
                    break
                elif cmd == "reset":
                    # SO_LINGER 0 turns close() into an RST, like a crashed server or a dead route.
                    sock = self.writer.get_extra_info("socket")
                    sock.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
                    print(f"{self.peer} reset")
                    break
                elif cmd:
                    self.send("What?\r\n")
                self.prompt()
        except (ConnectionError, IndexError):
            pass
        finally:
            if ticker:
                ticker.cancel()
            self.writer.close()
            print(f"{self.peer} gone")


async def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", type=int, default=4000)
    parser.add_argument("--mccp", action="store_true", help="offer MCCP2 compression")
    parser.add_argument("--password", help="require this password")
    parser.add_argument("--chatter", type=float, default=5, help="seconds between chatter lines")
    args = parser.parse_args()
    server = await asyncio.start_server(lambda r, w: Client(r, w, args).run(), "0.0.0.0", args.port)
    print(f"testmud on port {args.port}{' with MCCP2' if args.mccp else ''}")
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
