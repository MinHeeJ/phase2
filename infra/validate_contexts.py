"""Validate Docker context exclusions before invoking Docker; no third-party modules."""
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
compose = root / 'infra/docker-compose.yml'
required = {'node_modules', '.git', 'dist', 'build', '.next', 'coverage'}
contexts = re.findall(r'^\s+context:\s*(\S+)\s*$', compose.read_text(), re.M)
if not contexts:
    raise SystemExit('No build contexts declared')
for value in contexts:
    if '$' in value:
        raise SystemExit('Interpolated build context is forbidden')
    context = (compose.parent / value).resolve()
    if not context.is_relative_to(root):
        raise SystemExit('Build context escapes repository')
    ignore = context / '.dockerignore'
    if not ignore.is_file():
        raise SystemExit(f'Missing .dockerignore: {context.relative_to(root)}')
    entries = {line.strip().rstrip('/') for line in ignore.read_text().splitlines()}
    if required - entries:
        raise SystemExit(f'Missing exclusions: {context.relative_to(root)}: {sorted(required - entries)}')
    print(f'{context.relative_to(root)}: build context exclusions valid')
