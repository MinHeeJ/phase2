"""Reject missing exclusions in the actual Compose build contexts before building."""
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
compose = root / 'infra/docker-compose.yml'
contexts = re.findall(r'^\s+context:\s*(\S+)\s*$', compose.read_text(), re.M)
if not contexts:
    raise SystemExit('No Compose build contexts')
for relative in contexts:
    if '$' in relative:
        raise SystemExit('Build context must be static')
    context = (compose.parent / relative).resolve()
    if not context.is_relative_to(root):
        raise SystemExit('Build context must stay inside repository')
    ignored = set((context / '.dockerignore').read_text().splitlines())
    missing = {'node_modules', '.git', 'dist', 'build', '.next', 'coverage'} - {line.rstrip('/') for line in ignored}
    if missing:
        raise SystemExit(f'{relative}: missing {sorted(missing)}')
print('Compose build contexts have all required exclusions')
