"""OpenSignal flow and debug collectors."""

__all__ = ["main"]


def main() -> None:
    from opensignal_mirror.collector import main as run

    run()
