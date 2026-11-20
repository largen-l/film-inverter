"""Entry point: `python tool/run.py <command>` from a checkout, and the PyInstaller executable's main script."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from filminverter.cli import main  # noqa: E402

if __name__ == "__main__":
    sys.exit(main())
