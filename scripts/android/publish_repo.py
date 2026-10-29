"""Publish this repository's history to GitHub without the private parts.

The public repository (github.com/K11MCH1/Keeper-Recomped) carries the full history minus
the paths in EXCLUDE, removed from every commit. The rewrite is deterministic, so a
later run produces the same commits for the same history and pushes fast-forward.
Before pushing it refuses anything that looks like game data or a signing key.

    python scripts/android/publish_repo.py --dry-run     # rewrite and check only
    python scripts/android/publish_repo.py               # push main (and tags)
    python scripts/android/publish_repo.py --tag v0.1.0  # also tag the published HEAD

Pushing uses the GitHub CLI's login for that one command (no git config changes).
"""
import argparse
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
REMOTE = 'https://github.com/K11MCH1/Keeper-Recomped.git'
# Agent working notes and the Ghidra tooling stay local (the owner's decision).
EXCLUDE = ['AGENTS.md', 'CLAUDE.md', 'CE-Ghidra-MCP-Pack', '.mcp.json', 'tools/start-ghidra.ps1']
# Dungeon Keeper data, executables, archives, packages and keys never go public.
FORBIDDEN = {'.dat', '.tab', '.pal', '.raw', '.ogg', '.wav', '.mp3', '.sbk', '.col', '.dta', '.lif',
             '.tng', '.apt', '.wib', '.wlb', '.slb', '.clm', '.own', '.tex', '.lgt', '.exe', '.dll',
             '.smk', '.bik', '.apk', '.aab', '.jks', '.keystore', '.7z', '.iso'}
MAX_BLOB = 2 * 1024 * 1024


def git(*args, cwd, env=None, capture=True):
    result = subprocess.run(['git', *args], cwd=cwd, env=env, text=True,
                            capture_output=capture, encoding='utf-8')
    if result.returncode != 0:
        raise SystemExit(f'git {" ".join(args)} failed:\n{result.stderr if capture else ""}')
    return result.stdout if capture else ''


def check(repo):
    problems = []
    objects = git('rev-list', '--objects', '--all', cwd=repo).splitlines()
    paths = {line.split(' ', 1)[1] for line in objects if ' ' in line}
    for path in sorted(paths):
        top = path.split('/')[0]
        if path in EXCLUDE or top in EXCLUDE:
            problems.append(f'excluded path still present: {path}')
        if Path(path).suffix.lower() in FORBIDDEN:
            problems.append(f'forbidden file type: {path}')
    batch = subprocess.run(['git', 'cat-file', '--batch-check=%(objecttype) %(objectsize) %(rest)'],
                           cwd=repo, input='\n'.join(objects) + '\n', text=True, capture_output=True,
                           encoding='utf-8').stdout.splitlines()
    for line in batch:
        kind, size, *rest = line.split(' ', 2) + ['']
        if kind == 'blob' and int(size) > MAX_BLOB:
            problems.append(f'blob over {MAX_BLOB // 1024} KB: {rest[0]} ({int(size) // 1024} KB)')
    return problems, len(paths)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('--branch', default='master', help='Local branch to publish (pushed as main)')
    p.add_argument('--tag', help='Create this tag on the published HEAD and push it')
    p.add_argument('--dry-run', action='store_true', help='Rewrite and check, but do not push')
    p.add_argument('--keep', action='store_true', help='Keep the rewritten clone and print its path')
    args = p.parse_args()

    work = Path(tempfile.mkdtemp(prefix='keeper-recomped-publish-'))
    try:
        git('clone', '--quiet', '--no-local', '--single-branch', '--branch', args.branch, str(ROOT), str(work / 'repo'), cwd=ROOT)
        repo = work / 'repo'
        env = dict(os.environ, FILTER_BRANCH_SQUELCH_WARNING='1')
        removal = 'git rm -r -q --cached --ignore-unmatch -- ' + ' '.join(f"'{e}'" for e in EXCLUDE)
        git('filter-branch', '--index-filter', removal, '--prune-empty', '--', 'HEAD', cwd=repo, env=env)
        subprocess.run(['git', 'update-ref', '-d', f'refs/original/refs/heads/{args.branch}'], cwd=repo)
        git('remote', 'remove', 'origin', cwd=repo)
        git('reflog', 'expire', '--expire=now', '--all', cwd=repo)
        git('gc', '--quiet', '--prune=now', cwd=repo)
        problems, count = check(repo)
        commits = git('rev-list', '--count', 'HEAD', cwd=repo).strip()
        head = git('rev-parse', '--short', 'HEAD', cwd=repo).strip()
        print(f'rewritten: {commits} commits, {count} paths in history, HEAD {head}')
        if problems:
            print('REFUSING TO PUBLISH:\n  ' + '\n  '.join(problems[:40]))
            return 1
        print('checks passed: no excluded paths, game data, packages, keys or large blobs')
        if args.tag:
            git('tag', '-a', args.tag, '-m', args.tag, cwd=repo)
        if args.dry_run:
            return 0
        push = ['-c', 'credential.helper=', '-c', 'credential.helper=!gh auth git-credential',
                'push', REMOTE, f'HEAD:refs/heads/main']
        git(*push, cwd=repo, capture=False)
        if args.tag:
            git('-c', 'credential.helper=', '-c', 'credential.helper=!gh auth git-credential',
                'push', REMOTE, f'refs/tags/{args.tag}', cwd=repo, capture=False)
        print(f'pushed {head} to {REMOTE}' + (f' with tag {args.tag}' if args.tag else ''))
        return 0
    finally:
        if args.keep:
            print('rewritten clone:', work / 'repo')
        else:
            shutil.rmtree(work, ignore_errors=True)


if __name__ == '__main__':
    sys.exit(main())
