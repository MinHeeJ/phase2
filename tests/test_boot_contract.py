"""Boot regression checks: no Docker daemon, database, or credential output needed."""
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class BootContractTest(unittest.TestCase):
    def test_compose_discovers_both_build_units_without_injected_values(self):
        env = {k: v for k, v in os.environ.items()
               if not k.startswith(('AIOPS_SMOKE_', 'POSTGRES_', 'DATABASE_'))}
        configs = []
        for compose in ('docker-compose.yml', 'infra/docker-compose.yml'):
            result = subprocess.run(
                ['docker', 'compose', '-f', str(ROOT / compose),
                 'config', '--format', 'json'],
                env=env, capture_output=True, check=True, text=True)
            self.assertEqual(result.stderr, '')
            configs.append(json.loads(result.stdout))
        self.assertEqual(configs[0]['services'], configs[1]['services'])
        services = configs[0]['services']
        for service in ('backend', 'frontend'):
            self.assertEqual(Path(services[service]['build']['context']).name, service)
            self.assertEqual(services[service]['build']['dockerfile'], 'Dockerfile')
        self.assertNotIn('ports', services['db'])
        self.assertIn('POSTGRES_PASSWORD_FILE', services['db']['environment'])
        self.assertNotIn('POSTGRES_PASSWORD', services['db']['environment'])
        self.assertNotIn('DATABASE_PASSWORD', services['backend']['environment'])
        self.assertEqual(services['db']['depends_on']['db-credentials']['condition'],
                         'service_completed_successfully')

    def test_initializer_is_silent_random_and_restart_safe(self):
        with tempfile.TemporaryDirectory(dir=os.environ['TMPDIR']) as directory:
            command = ['sh', str(ROOT / 'infra/init-db-credentials.sh'), directory]
            def initialize():
                result = subprocess.run(command, capture_output=True, check=True)
                self.assertEqual(result.stdout, b'')
                self.assertEqual(result.stderr, b'')
            initialize()
            secret = Path(directory) / 'spring.datasource.password'
            # Do not use assertEqual on credential bytes: failure messages must not expose them.
            first = secret.read_bytes()
            self.assertTrue(bool(re.fullmatch(rb'[0-9a-f]{64}', first)))
            self.assertEqual(secret.stat().st_mode & 0o777, 0o444)
            initialize()
            self.assertTrue(secret.read_bytes() == first)
            self.assertEqual(len(list(Path(directory).iterdir())), 1)
            secret.unlink()
            initialize()
            self.assertTrue(secret.read_bytes() != first)

    def test_runtime_and_initial_guide_agree(self):
        runtime = json.loads((ROOT / 'aiops.runtime-check.json').read_text())
        guide = json.loads((ROOT / 'aiops.initial-login.json').read_text())
        self.assertEqual(runtime['schema_version'], 1)
        self.assertFalse(runtime['auth']['required'])
        self.assertFalse(guide['auth_required'])
        self.assertEqual(set(guide), {'schema_version', 'auth_required', 'guide_path'})
        self.assertEqual(runtime['backend']['service'], 'backend')
        self.assertEqual(runtime['backend']['container_port'], 8080)
        self.assertEqual(runtime['first_menu']['path'], '/api/menus')
        self.assertEqual(runtime['first_menu']['route'], '/UI-16')


if __name__ == '__main__':
    unittest.main()
