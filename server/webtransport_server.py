#!/usr/bin/env python3
"""WebTransport probe server — HTTP/3 datagram echo for browser probes."""

from __future__ import annotations

import argparse
import asyncio
import logging
import os
import time
from collections import deque
from email.utils import formatdate
from typing import Callable, Deque, Optional, Union

import aioquic
from aioquic.asyncio import QuicConnectionProtocol, serve
from aioquic.h3.connection import H3_ALPN, H3Connection
from aioquic.h3.events import (
    DatagramReceived,
    DataReceived,
    H3Event,
    HeadersReceived,
    WebTransportStreamDataReceived,
)
from aioquic.quic.configuration import QuicConfiguration
from aioquic.quic.events import ProtocolNegotiated, QuicEvent
from aioquic.tls import SessionTicket

from probe_handler import ProbeHandler

logger = logging.getLogger(__name__)
PROBE_HANDLER = ProbeHandler()
WT_PATH = "/wt-probe"
SERVER_NAME = "netprobe-wt/" + aioquic.__version__


class WebTransportHandler:
    def __init__(
        self,
        *,
        connection: H3Connection,
        scope: dict,
        stream_id: int,
        transmit: Callable[[], None],
    ) -> None:
        self.accepted = False
        self.closed = False
        self.connection = connection
        self.http_event_queue: Deque[H3Event] = deque()
        self.queue: asyncio.Queue[dict] = asyncio.Queue()
        self.scope = scope
        self.stream_id = stream_id
        self.transmit = transmit

    def http_event_received(self, event: H3Event) -> None:
        if self.closed:
            return
        if self.accepted:
            if isinstance(event, DatagramReceived):
                self.queue.put_nowait(
                    {"data": event.data, "type": "webtransport.datagram.receive"},
                )
            elif isinstance(event, WebTransportStreamDataReceived):
                self.queue.put_nowait(
                    {
                        "data": event.data,
                        "stream": event.stream_id,
                        "type": "webtransport.stream.receive",
                    },
                )
        else:
            self.http_event_queue.append(event)

    async def run_asgi(self, app) -> None:
        self.queue.put_nowait({"type": "webtransport.connect"})
        try:
            await app(self.scope, self.receive, self.send)
        finally:
            if not self.closed:
                await self.send({"type": "webtransport.close"})

    async def receive(self) -> dict:
        return await self.queue.get()

    async def send(self, message: dict) -> None:
        end_stream = False
        if message["type"] == "webtransport.accept":
            self.accepted = True
            headers = [
                (b":status", b"200"),
                (b"server", SERVER_NAME.encode()),
                (b"date", formatdate(time.time(), usegmt=True).encode()),
                (b"sec-webtransport-http3-draft", b"draft02"),
            ]
            self.connection.send_headers(stream_id=self.stream_id, headers=headers)
            while self.http_event_queue:
                self.http_event_received(self.http_event_queue.popleft())
        elif message["type"] == "webtransport.close":
            if not self.accepted:
                self.connection.send_headers(
                    stream_id=self.stream_id,
                    headers=[(b":status", b"403")],
                )
            end_stream = True
        elif message["type"] == "webtransport.datagram.send":
            self.connection.send_datagram(stream_id=self.stream_id, data=message["data"])
        elif message["type"] == "webtransport.stream.send":
            self.connection._quic.send_stream_data(
                stream_id=message["stream"],
                data=message["data"],
            )

        if end_stream:
            self.connection.send_data(stream_id=self.stream_id, data=b"", end_stream=True)
            self.closed = True
        self.transmit()


class WebTransportServerProtocol(QuicConnectionProtocol):
    def __init__(self, *args, **kwargs) -> None:
        super().__init__(*args, **kwargs)
        self._handlers: dict[int, WebTransportHandler] = {}
        self._http: Optional[H3Connection] = None

    def http_event_received(self, event: H3Event) -> None:
        if isinstance(event, HeadersReceived) and event.stream_id not in self._handlers:
            method = ""
            path = ""
            protocol = None
            for header, value in event.headers:
                if header == b":method":
                    method = value.decode()
                elif header == b":path":
                    path = value.decode()
                elif header == b":protocol":
                    protocol = value.decode()

            if method != "CONNECT" or protocol != "webtransport":
                self._http.send_headers(
                    stream_id=event.stream_id,
                    headers=[(b":status", b"404")],
                )
                self.transmit()
                return

            client_addr = self._http._quic._network_paths[0].addr
            client = (client_addr[0], client_addr[1])
            scope = {
                "client": client,
                "headers": [],
                "http_version": "3",
                "method": method,
                "path": path,
                "query_string": b"",
                "raw_path": path.encode(),
                "root_path": "",
                "scheme": "https",
                "type": "webtransport",
            }
            handler = WebTransportHandler(
                connection=self._http,
                scope=scope,
                stream_id=event.stream_id,
                transmit=self.transmit,
            )
            self._handlers[event.stream_id] = handler
            asyncio.ensure_future(handler.run_asgi(application))
        elif event.stream_id in self._handlers:
            self._handlers[event.stream_id].http_event_received(event)
        elif isinstance(event, WebTransportStreamDataReceived):
            handler = self._handlers.get(event.session_id)
            if handler:
                handler.http_event_received(event)

    def quic_event_received(self, event: QuicEvent) -> None:
        if isinstance(event, ProtocolNegotiated):
            if event.alpn_protocol in H3_ALPN:
                self._http = H3Connection(self._quic, enable_webtransport=True)
        if self._http is not None:
            for http_event in self._http.handle_event(event):
                self.http_event_received(http_event)


class SessionTicketStore:
    def __init__(self) -> None:
        self.tickets: dict[bytes, SessionTicket] = {}

    def add(self, ticket: SessionTicket) -> None:
        self.tickets[ticket.ticket] = ticket

    def pop(self, label: bytes) -> Optional[SessionTicket]:
        return self.tickets.pop(label, None)


async def application(scope, receive, send) -> None:
    if scope["type"] != "webtransport":
        return
    if scope.get("path") != WT_PATH:
        await send({"type": "webtransport.close"})
        return

    client = scope.get("client", ("?", 0))
    session_key = f"wt:{client[0]}:{client[1]}:{id(scope)}"
    logger.info("WT connect session=%s path=%s", session_key, scope.get("path"))

    while True:
        message = await receive()
        msg_type = message.get("type")
        if msg_type == "webtransport.connect":
            await send({"type": "webtransport.accept"})
        elif msg_type == "webtransport.datagram.receive":
            reply = PROBE_HANDLER.handle(session_key, message["data"])
            if reply:
                await send({"type": "webtransport.datagram.send", "data": reply})
        elif msg_type == "webtransport.close":
            PROBE_HANDLER.drop_session(session_key)
            break


async def run_server(
    host: str,
    port: int,
    configuration: QuicConfiguration,
    retry: bool,
) -> None:
    ticket_store = SessionTicketStore()
    await serve(
        host,
        port,
        configuration=configuration,
        create_protocol=WebTransportServerProtocol,
        session_ticket_fetcher=ticket_store.pop,
        session_ticket_handler=ticket_store.add,
        retry=retry,
    )
    await asyncio.Future()


def main() -> int:
    parser = argparse.ArgumentParser(description="NetProbe WebTransport probe server")
    parser.add_argument("--host", default=os.environ.get("NETPROBE_WT_HOST", "0.0.0.0"))
    parser.add_argument("--port", type=int, default=int(os.environ.get("NETPROBE_WT_PORT", "4433")))
    parser.add_argument(
        "--certificate",
        default=os.environ.get(
            "NETPROBE_WT_CERT",
            "/etc/letsencrypt/live/netprobe.xyz/fullchain.pem",
        ),
    )
    parser.add_argument(
        "--private-key",
        default=os.environ.get(
            "NETPROBE_WT_KEY",
            "/etc/letsencrypt/live/netprobe.xyz/privkey.pem",
        ),
    )
    parser.add_argument("-v", "--verbose", action="store_true")
    args = parser.parse_args()

    logging.basicConfig(
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
        level=logging.DEBUG if args.verbose else logging.INFO,
    )

    configuration = QuicConfiguration(
        alpn_protocols=H3_ALPN,
        is_client=False,
        max_datagram_frame_size=65536,
    )
    try:
        configuration.load_cert_chain(args.certificate, args.private_key)
    except OSError as exc:
        logger.error(
            "Failed to load TLS certificate %s / %s: %s",
            args.certificate,
            args.private_key,
            exc,
        )
        return 1

    logger.info("WebTransport probe listening on %s:%s path=%s", args.host, args.port, WT_PATH)
    try:
        asyncio.run(
            run_server(
                host=args.host,
                port=args.port,
                configuration=configuration,
                retry=False,
            ),
        )
    except KeyboardInterrupt:
        pass
    except OSError as exc:
        logger.error("WebTransport server failed on %s:%s: %s", args.host, args.port, exc)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
