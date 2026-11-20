#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Regression test: PS Link edit backups must never be Android resources."""
from __future__ import annotations

import tempfile
from pathlib import Path

from prepare_pslink_complete_fix import relocate_legacy_resource_backups, write_transaction


def main() -> None:
    with tempfile.TemporaryDirectory(prefix="pslink-backup-test-") as temporary_root:
        root = Path(temporary_root)
        resource_dir = root / "third_party/chiaki-ng/android/app/src/main/res/layout"
        resource_dir.mkdir(parents=True)
        target = resource_dir / "activity_stream.xml"
        target.write_text("<layout>old</layout>\n", encoding="utf-8")

        backups = write_transaction(root, {target: "<layout>new</layout>\n"})
        assert len(backups) == 1, f"Expected one preserved backup, received {len(backups)}"
        backup = backups[0]
        assert backup.is_file(), "Backup was not created"
        assert backup.read_text(encoding="utf-8") == "<layout>old</layout>\n", "Backup bytes changed"
        assert target.read_text(encoding="utf-8") == "<layout>new</layout>\n", "Target was not updated"
        assert ".pslink-backups" in backup.parts, "Backup is not under the dedicated backup directory"
        assert not list(resource_dir.glob("*.bak")), "A backup was left inside Android res/layout"

        legacy = resource_dir / "activity_stream.xml.before-pslink-complete-fix-legacy.bak"
        legacy.write_bytes(b"legacy-backup-preserved")
        moved = relocate_legacy_resource_backups(root)
        assert len(moved) == 1, f"Expected one legacy backup to move, received {len(moved)}"
        assert not legacy.exists(), "Legacy backup remains inside Android resources"
        assert moved[0].read_bytes() == b"legacy-backup-preserved", "Legacy backup contents changed"
        assert ".pslink-backups" in moved[0].parts, "Legacy backup was not moved to the safe directory"

    print("PS Link backup storage regression test passed.")


if __name__ == "__main__":
    main()
