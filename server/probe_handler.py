"""Shared probe echo handler for UDP server and WebTransport datagrams."""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass

from udp_probe_protocol import (
    VERSION,
    VERSION_V2,
    is_probe_request,
    is_summary_request,
    pack_reply,
    pack_reply_v2,
    pack_summary_reply,
    probe_request_version,
    unpack_request,
    unpack_request_v2,
    unpack_summary_request,
)

logger = logging.getLogger(__name__)


@dataclass
class ProbeRoundState:
    round_id: int
    rx_count: int = 0
    tx_count: int = 0
    last_activity: float = 0.0


class ProbeHandler:
    def __init__(self) -> None:
        self._rounds: dict[str, ProbeRoundState] = {}

    def handle(self, session_key: str, data: bytes, *, echo: bool = True) -> bytes | None:
        if is_summary_request(data):
            return self._handle_summary(session_key, data)
        if is_probe_request(data):
            return self._handle_probe(session_key, data, echo=echo)
        return None

    def drop_session(self, session_key: str) -> None:
        self._rounds.pop(session_key, None)

    def _get_round(self, session_key: str, round_id: int, now: float) -> ProbeRoundState:
        state = self._rounds.get(session_key)
        if state is None or state.round_id != round_id:
            state = ProbeRoundState(round_id=round_id, last_activity=now)
            self._rounds[session_key] = state
        else:
            state.last_activity = now
        return state

    def _handle_probe_v1(self, session_key: str, data: bytes, echo: bool) -> bytes | None:
        t2 = time.time_ns()
        try:
            req = unpack_request(data)
        except ValueError as exc:
            logger.warning("PROBE_BAD session=%s bytes=%d err=%s", session_key, len(data), exc)
            return None
        if not echo:
            return None
        t3 = time.time_ns()
        logger.debug(
            "PROBE v1 session=%s seq=%d t1=%d t2=%d t3=%d",
            session_key,
            req.seq,
            req.t1,
            t2,
            t3,
        )
        return pack_reply(req.seq, req.t1, t2, t3)

    def _handle_probe_v2(self, session_key: str, data: bytes, echo: bool) -> bytes | None:
        t2 = time.time_ns()
        try:
            req = unpack_request_v2(data)
        except ValueError as exc:
            logger.warning("PROBE_V2_BAD session=%s bytes=%d err=%s", session_key, len(data), exc)
            return None

        now = time.time()
        state = self._get_round(session_key, req.round_id, now)
        state.rx_count += 1
        if not echo:
            return None

        t3 = time.time_ns()
        state.tx_count += 1
        logger.debug(
            "PROBE v2 session=%s round=%d seq=%d rx=%d tx=%d",
            session_key,
            req.round_id,
            req.seq,
            state.rx_count,
            state.tx_count,
        )
        return pack_reply_v2(
            req.round_id,
            req.seq,
            req.t1,
            t2,
            t3,
            state.rx_count,
            state.tx_count,
        )

    def _handle_probe(self, session_key: str, data: bytes, echo: bool) -> bytes | None:
        version = probe_request_version(data)
        if version == VERSION_V2:
            return self._handle_probe_v2(session_key, data, echo)
        if version == VERSION:
            return self._handle_probe_v1(session_key, data, echo)
        logger.warning("PROBE_BAD_VERSION session=%s version=%s", session_key, version)
        return None

    def _handle_summary(self, session_key: str, data: bytes) -> bytes | None:
        try:
            round_id = unpack_summary_request(data)
        except ValueError as exc:
            logger.warning("SUMMARY_BAD session=%s bytes=%d err=%s", session_key, len(data), exc)
            return None

        state = self._rounds.get(session_key)
        if state is None or state.round_id != round_id:
            logger.info("SUMMARY session=%s round=%d rx=0 tx=0 (unknown round)", session_key, round_id)
            return pack_summary_reply(round_id, 0, 0)

        logger.info(
            "SUMMARY session=%s round=%d rx=%d tx=%d",
            session_key,
            round_id,
            state.rx_count,
            state.tx_count,
        )
        reply = pack_summary_reply(round_id, state.rx_count, state.tx_count)
        del self._rounds[session_key]
        return reply

    def expire_idle(self, idle_timeout_s: float) -> None:
        now = time.time()
        expired = [
            key
            for key, state in self._rounds.items()
            if now - state.last_activity >= idle_timeout_s
        ]
        for key in expired:
            del self._rounds[key]
