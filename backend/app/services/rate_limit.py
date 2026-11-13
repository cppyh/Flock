"""초대 코드 대입을 막는 프로세스 내 요청 제한."""

from __future__ import annotations

import threading
import time
from collections import defaultdict, deque
from collections.abc import Callable

WINDOW_SECONDS = 60


class SlidingWindowLimiter:
    def __init__(self, limit: int, clock: Callable[[], float] = time.monotonic):
        self._limit = limit
        self._clock = clock
        self._lock = threading.Lock()
        self._attempts: dict[str, deque[float]] = defaultdict(deque)

    def allow(self, key: str) -> bool:
        now = self._clock()
        with self._lock:
            attempts = self._attempts[key]
            while attempts and attempts[0] <= now - WINDOW_SECONDS:
                attempts.popleft()
            if len(attempts) >= self._limit:
                return False
            attempts.append(now)
            return True
