#!/usr/bin/env python3
"""
Reconcile catalog vs. active S3 backend.

Finds assets whose ciphertext objects are missing from the configured bucket
(e.g. leftovers from a backend switch, or externally deleted objects) and
tombstones them by default, so the incremental sync propagates the deletion to
every client. --purge hard-deletes the rows instead.

Usage:
  python3 scripts/reconcile_missing.py            # tombstone missing assets
  python3 scripts/reconcile_missing.py --purge    # hard-delete missing assets
  python3 scripts/reconcile_missing.py --dry-run  # report only

This is the manual seed of the scheduled reconciliation worker from the plan
(§ backup workflow: "scheduled reconciliation", GC with cross-checks).
"""

import json
import os
import subprocess
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def load_env():
    env = {}
    path = os.path.join(REPO, ".env")
    if not os.path.exists(path):
        sys.exit("No .env found at repo root — nothing to reconcile against.")
    with open(path) as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                env[k.strip()] = v.strip()
    return env


def sh(cmd, **kw):
    kw.setdefault("capture_output", True)
    kw.setdefault("text", True)
    return subprocess.run(cmd, **kw)


def db(env, sql):
    """Run SQL against the compose Postgres, return rows as lists of strings."""
    r = sh(
        ["docker", "exec", "fusion-photos-db-1", "psql", "-U", "fusion", "-d",
         "fusion", "-A", "-t", "-F", "\t", "-c", sql],
        capture_output=True,
    )
    if r.returncode != 0:
        sys.exit(f"psql failed: {r.stderr}")
    return [line.split("\t") for line in r.stdout.splitlines() if line.strip()]


def list_s3_keys(env):
    r = sh(
        ["docker", "run", "--rm",
         "-e", f"AWS_ACCESS_KEY_ID={env['S3_ACCESS_KEY']}",
         "-e", f"AWS_SECRET_ACCESS_KEY={env['S3_SECRET_KEY']}",
         "-e", f"AWS_DEFAULT_REGION={env.get('S3_REGION', 'us-east-1')}",
         "public.ecr.aws/aws-cli/aws-cli:latest",
         "s3api", "list-objects-v2", "--bucket", env["S3_BUCKET"]],
        capture_output=True,
    )
    if r.returncode != 0:
        sys.exit(f"S3 list failed: {r.stderr}")
    out = json.loads(r.stdout or "{}")
    return {o["Key"] for o in out.get("Contents", [])}


def main():
    purge = "--purge" in sys.argv
    dry = "--dry-run" in sys.argv
    env = load_env()

    rows = db(env, "SELECT a.id, r.object_key FROM assets a "
                   "JOIN asset_resources r ON r.asset_id = a.id "
                   "WHERE a.deleted = false")
    s3_keys = list_s3_keys(env)

    assets: dict[str, list[str]] = {}
    for asset_id, key in rows:
        assets.setdefault(asset_id, []).append(key)

    missing = {
        aid: [k for k in keys if k not in s3_keys]
        for aid, keys in assets.items()
    }
    orphans = {aid: ks for aid, ks in missing.items() if ks}

    if not orphans:
        print(f"OK: {len(assets)} assets, all objects present in {env['S3_BUCKET']}")
        return

    print(f"Found {len(orphans)} asset(s) with missing objects in {env['S3_BUCKET']}:")
    for aid, ks in orphans.items():
        for k in ks:
            print(f"  MISSING  {k}")

    if dry:
        print("\n(dry run — no changes made)")
        return

    action = "DELETE FROM" if purge else "UPDATE assets SET deleted = true WHERE"
    ids = ", ".join(f"'{aid}'" for aid in orphans)
    sql = f"{action} id IN ({ids})"
    r = sh(["docker", "exec", "fusion-photos-db-1", "psql", "-U", "fusion", "-d",
            "fusion", "-c", sql], capture_output=True)
    if r.returncode != 0:
        sys.exit(f"DB update failed: {r.stderr}")
    print(f"\n{'Purged' if purge else 'Tombstoned'} {len(orphans)} asset(s). "
          "Clients pick this up on their next sync.")


if __name__ == "__main__":
    main()
