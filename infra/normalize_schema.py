"""Materialize the supplied PostgreSQL snapshot; never run against a deployed migration."""
from pathlib import Path
import hashlib
import json
import re

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'example/si_cost_schema_pg16_std.sql'
SCOPED = {'cmn.cd_grp', 'cmn.cd_dtl', 'cmn.cd_hist', 'cmn.excel_map', 'cmn.oprt_std',
          'fin.mth_cls', 'sys.menu', 'sys.role', 'sys.role_menu_auth'}


def normalize(source):
    sql = re.sub(r'^DROP SCHEMA[^;]+;\s*\n', '', source, flags=re.M)
    return re.sub(r'\bCREATE (TABLE|INDEX|SEQUENCE) (?!IF NOT EXISTS)',
                  r'CREATE \1 IF NOT EXISTS ', sql)


def main():
    source = SOURCE.read_text(encoding='utf-8')
    sql = normalize(source)
    tables = list(re.finditer(r'CREATE TABLE ([a-z_]+\.[a-z_]+) \((.*?)\n\);', source, re.S))
    assert len(tables) == 80
    assert len(re.findall(r'^CREATE SCHEMA ', source, re.M)) == 11
    table_comments = dict(re.findall(r"COMMENT ON TABLE ([\w.]+) IS '((?:[^']|'')*)';", source))
    column_comments = dict(re.findall(r"COMMENT ON COLUMN ([\w.]+) IS '((?:[^']|'')*)';", source))
    references = {(t, c.strip()) for t, cols in re.findall(
        r'ALTER TABLE ([\w.]+) ADD CONSTRAINT \w+ FOREIGN KEY \(([^)]+)\)', source)
        for c in cols.split(',')}
    additions = []
    manifest = []
    for match in tables:
        table, body = match.groups()
        pk = re.search(r'PRIMARY KEY \(([^)]+)\)', body).group(1).replace(' ', '').split(',')
        columns = []
        for line in body.splitlines():
            found = re.match(r'\s+(\w+)\s+(BIGINT|VARCHAR\(\d+\)|CHAR\(\d+\)|INT|SMALLINT|DECIMAL\(\d+,\d+\)|TIMESTAMP|DATE|TEXT|JSONB)(.*)', line)
            if not found:
                continue
            name, typ, rest = found.groups()
            default = re.search(r"DEFAULT (CURRENT_TIMESTAMP|'[^']*'|[\d.]+)", rest)
            columns.append({'name': name, 'type': typ, 'nullable': 'NOT NULL' not in rest and name not in pk,
                            'identity': 'AS IDENTITY' in rest,
                            'default': default.group(1) if default else None})
            key = table + '.' + name
            old = column_comments.get(key, name)
            if (name.endswith('_id') or name.endswith('_by')) and (table, name) not in references and name not in pk:
                intent = 'sys.user_info.user_id' if name.endswith('_by') or 'user_id' in name else '업무 원천 식별자'
                if key == 'cmn.cd_hist.cd_grp_id':
                    intent = 'cmn.cd_grp.cd_grp_id 또는 PARAM 기준값 구분'
                if key == 'sys.role.copy_role_id':
                    intent = 'sys.role.role_id'
                additions.append(f"COMMENT ON COLUMN {key} IS '{old} — {intent} 참조 의도 (FK 미선언)';")
            if name == 'updated_at':
                trigger = re.search(r'CREATE TRIGGER \w+ BEFORE UPDATE ON ' + re.escape(table) + r' ', source)
                owner = '애플리케이션 변경 시 갱신; BEFORE UPDATE 트리거에서 최종 갱신' if trigger else '해당 업무 애플리케이션 저장 시 갱신'
                additions.append(f"COMMENT ON COLUMN {key} IS '{old} — {owner}';")
            if 'GENERATED ALWAYS AS' in rest:
                additions.append(f"COMMENT ON COLUMN {key} IS '{old} — PostgreSQL이 원천 열 INSERT/UPDATE 시 재계산';")
        if table not in SCOPED:
            additions.append(f"COMMENT ON TABLE {table} IS '{table_comments[table]} — 현재 미사용, 후속 업무 연동 예정';")
        if table in SCOPED:
            foreign = []
            for f in re.finditer(r'ALTER TABLE ' + re.escape(table) + r' ADD CONSTRAINT (\w+) FOREIGN KEY \(([^)]+)\) REFERENCES ([\w.]+) \(([^)]+)\)', source):
                name, cols, parent, targets = f.groups()
                foreign.append({'name': name, 'columns': cols.replace(' ', '').split(','), 'parent': parent, 'targets': targets.replace(' ', '').split(',')})
            manifest.append({'table': table, 'columns': columns, 'primaryKey': pk, 'foreignKeys': foreign,
                             'uniqueKeys': [v.replace(' ', '').split(',') for v in re.findall(r'UNIQUE \(([^)]+)\)', body)]})
    additions.extend([
        "COMMENT ON COLUMN cmn.cd_hist.cd_chg_ty_cd IS 'ADD:추가|UPDATE:수정|DISABLE:사용중지|ENABLE:재사용';",
        "COMMENT ON COLUMN cmn.excel_map.map_role_cd IS 'MAIN:주|SUB:보조';",
        "COMMENT ON COLUMN cmn.excel_map.use_yn IS 'Y:사용|N:사용안함';",
        "COMMENT ON COLUMN sys.menu.use_yn IS 'Y:사용|N:사용안함';",
        "COMMENT ON COLUMN sys.menu.mbl_expsr_yn IS 'Y:표시|N:비노출';",
        "COMMENT ON COLUMN sys.role.use_yn IS 'Y:사용|N:사용안함';",
        "COMMENT ON COLUMN mst.dev_work_stts_hist.work_stts_cd IS 'VACATION:휴가|PARENTAL:육아휴직|UNPAID:무급휴가|LEAVE:휴직|RESIGN:퇴사';",
        "COMMENT ON COLUMN hr.cand_cntct_hist.cntct_stts_cd IS 'NONE:연락전|DONE:연락함|INTV:면접예정|HOLD:보류|SIGNED:계약완료';"
    ])
    artifact = sql + '\n-- Business, reference and audit metadata (no behavioral changes).\n' + '\n'.join(additions) + '\n'
    resources = ROOT / 'backend/src/main/resources'
    for path in [resources / 'db/migration/V1__schema.sql', resources / 'schema.sql']:
        path.parent.mkdir(parents=True, exist_ok=True)
        if path.exists():
            raise RuntimeError(f'Refusing to replace frozen artifact: {path.name}')
        path.write_text(artifact, encoding='utf-8')
    (resources / 'schema-metadata.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    fixture = ROOT / 'backend/src/test/resources/schema/original.sql'
    fixture.parent.mkdir(parents=True, exist_ok=True)
    fixture.write_bytes(SOURCE.read_bytes())
    (resources / 'schema-origin.json').write_text(json.dumps({'source': 'example/si_cost_schema_pg16_std.sql',
        'sha256': hashlib.sha256(SOURCE.read_bytes()).hexdigest(),
        'normalization': ['remove executable DROP SCHEMA', 'CREATE TABLE/INDEX/SEQUENCE IF NOT EXISTS', 'append metadata comments'],
        'tables': len(tables), 'schemas': 11}, indent=2) + '\n')
    print('Imported 80 tables and 11 schemas; original source retained; V1 and schema.sql normalized identically.')


if __name__ == '__main__':
    main()
