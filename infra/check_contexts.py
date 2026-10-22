from pathlib import Path
import re


def inspect_contexts(root: Path) -> list[str]:
    compose = root / 'infra/docker-compose.yml'
    required = {'node_modules', '.git', 'dist', 'build', '.next', 'coverage'}
    errors = []
    for raw in re.findall(r'^\s+context:\s*(.+?)\s*$', compose.read_text(), re.M):
        if '${' in raw:
            errors.append('build context must be static')
            continue
        context = (compose.parent / raw.strip('\"\'')).resolve()
        ignore = context / '.dockerignore'
        if not ignore.is_file():
            errors.append(f'Missing {ignore.relative_to(root)}')
            continue
        patterns = {line.strip().rstrip('/') for line in ignore.read_text().splitlines() if line.strip() and not line.startswith('#')}
        missing = required - patterns
        if missing:
            errors.append(f'{ignore.relative_to(root)} lacks {sorted(missing)}')
    return errors


if __name__ == '__main__':
    root = Path(__file__).resolve().parent.parent
    errors = inspect_contexts(root)
    if errors:
        raise SystemExit('\n'.join(errors))
    print('Build contexts and dockerignore exclusions verified.')
