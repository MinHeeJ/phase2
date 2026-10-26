"""Regression checks for project-isolated, daemon-allocated Compose networks."""
import json
import os
from pathlib import Path
import shlex
import subprocess
import unittest


COMPOSE = Path(__file__).resolve().with_name("docker-compose.yml")


class ComposeNetworkTest(unittest.TestCase):
    def config(self, project):
        environment = os.environ.copy()
        # A previous local workaround must not pin every QA project to one subnet.
        environment["COMPOSE_SUBNET"] = "10.253.7.0/24"
        result = subprocess.run(
            ["docker", "compose", "-f", str(COMPOSE), "--project-name", project,
             "config", "--format", "json"],
            env=environment, capture_output=True, timeout=30,
        )
        self.assertEqual(result.returncode, 0, "Compose configuration must be valid")
        return json.loads(result.stdout)

    def test_projects_do_not_share_a_fixed_subnet_or_external_network(self):
        first = self.config("network-regression-a")["networks"]["default"]
        second = self.config("network-regression-b")["networks"]["default"]
        for network in (first, second):
            self.assertFalse(network.get("ipam", {}).get("config"),
                             "Docker must allocate a non-overlapping subnet per project")
            self.assertFalse(network.get("external", False))
        self.assertNotEqual(first["name"], second["name"])

    def test_database_remains_internal_and_health_dependencies_are_preserved(self):
        services = self.config("network-regression-a")["services"]
        self.assertFalse(services["db"].get("ports"))
        for name in ("db", "backend", "frontend"):
            self.assertTrue(services[name]["healthcheck"]["test"])
        self.assertEqual(services["backend"]["depends_on"]["db"]["condition"], "service_healthy")
        self.assertEqual(services["frontend"]["depends_on"]["backend"]["condition"], "service_healthy")

    def test_database_health_requires_tcp_and_an_existing_database(self):
        probe = self.config("network-regression-a")["services"]["db"]["healthcheck"]["test"]
        self.assertEqual(probe[0], "CMD-SHELL")
        tokens = shlex.split(probe[1])
        # The entrypoint's temporary initialization server only accepts Unix sockets.
        # pg_isready alone can also succeed when the requested database is absent.
        for executable in ("pg_isready", "psql"):
            command = tokens[tokens.index(executable):]
            self.assertEqual(command[command.index("-h") + 1], "127.0.0.1")
            self.assertEqual(command[command.index("-p") + 1], "5432")
            self.assertIn("-U", command)
            self.assertIn("-d", command)
        self.assertIn("&&", tokens)
        self.assertEqual(tokens[tokens.index("-c") + 1].rstrip(";"), "SELECT 1")
        self.assertIn("ON_ERROR_STOP=1", tokens)
        self.assertIn("-w", tokens)
        self.assertIn("PGPASSWORD=", probe[1])
        self.assertIn(">/dev/null 2>&1", probe[1])

    def test_probe_rejects_initialization_server_and_missing_database(self):
        database = self.config("network-regression-a")["services"]["db"]
        probe = database["healthcheck"]["test"][1].replace("$$", "$")
        # Exercise the actual image and probe, not just their configuration text.
        # Everything is ephemeral, unprivileged and disconnected from app data.
        script = """
set -eu
export POSTGRES_USER="$(id -un)" POSTGRES_DB="$(id -un)"
export POSTGRES_PASSWORD=regression-only
initdb -D /tmp/readiness-data >/dev/null 2>&1
trap 'pg_ctl -D /tmp/readiness-data -m immediate stop >/dev/null 2>&1 || true' EXIT
pg_ctl -D /tmp/readiness-data -o "-c listen_addresses=''" -w start >/dev/null 2>&1
pg_isready -q -U "$POSTGRES_USER" -d "$POSTGRES_DB"
if PROBE; then exit 11; fi
pg_ctl -D /tmp/readiness-data -m fast -w stop >/dev/null 2>&1
pg_ctl -D /tmp/readiness-data -o "-c listen_addresses=127.0.0.1" -w start >/dev/null 2>&1
PROBE
export POSTGRES_DB=readiness_missing_database
pg_isready -q -h 127.0.0.1 -p 5432 -U "$POSTGRES_USER" -d "$POSTGRES_DB"
if PROBE; then exit 12; fi
export POSTGRES_DB="$(id -un)"
pg_ctl -D /tmp/readiness-data -m fast -w stop >/dev/null 2>&1
if PROBE; then exit 13; fi
pg_ctl -D /tmp/readiness-data -o "-c listen_addresses=127.0.0.1" -w start >/dev/null 2>&1
PROBE
""".replace("PROBE", probe)
        result = subprocess.run(
            ["docker", "run", "--rm", "--log-driver", "none", "--network", "none",
             "--user", "postgres", "--entrypoint", "sh", database["image"], "-c", script],
            capture_output=True, timeout=90,
        )
        self.assertEqual(result.returncode, 0,
                         "DB probe must reject socket-only initialization and absent DBs, "
                         "reject a stopped server, and accept initial and restarted TCP servers")
        self.assertEqual(result.stdout, b"", "Probe must not expose connection or query output")
        self.assertEqual(result.stderr, b"", "Probe must not expose connection diagnostics")


if __name__ == "__main__":
    unittest.main()
