"""Start the generated trial app with its separate initial-login artifact, without logging secrets."""
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def main():
    try:
        guide = json.loads((ROOT / 'aiops.initial-login.json').read_text(encoding='utf-8'))
        env = os.environ.copy()
        if guide['auth_required']:
            env.setdefault('INITIAL_ADMIN_LOGIN_ID', guide['login_id'])
            env.setdefault('INITIAL_ADMIN_PASSWORD', guide['password'])
            env.setdefault('INITIAL_ADMIN_EMAIL', 'trial-administrator@example.invalid')
        result = subprocess.run(['docker', 'compose', '-f', str(ROOT / 'infra/docker-compose.yml'),
                                 'up', '--build', '-d', '--wait', 'db', 'backend', 'frontend'],
                                env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if result.returncode:
            print('app_start=FAIL (inspect service state without printing sensitive logs)')
            return 1
    except (OSError, ValueError, KeyError, TypeError):
        print('app_start=FAIL (initial-login artifact or runtime unavailable)')
        return 1
    print('app_start=PASS initial_login_guide=README.md')
    return 0


if __name__ == '__main__':
    sys.exit(main())
