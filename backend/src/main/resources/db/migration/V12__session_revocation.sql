-- New menus and roles are closed even to administrators until explicitly granted.
CREATE OR REPLACE FUNCTION sys.initialize_menu_permissions() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO sys.role_menu_auth(role_id,menu_id)
    SELECT role_id,NEW.menu_id FROM sys.role ON CONFLICT DO NOTHING;
    RETURN NEW;
END;
$$;
CREATE TRIGGER initialize_menu_permissions AFTER INSERT ON sys.menu
FOR EACH ROW EXECUTE FUNCTION sys.initialize_menu_permissions();
CREATE OR REPLACE FUNCTION sys.initialize_role_permissions() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO sys.role_menu_auth(role_id,menu_id)
    SELECT NEW.role_id,menu_id FROM sys.menu ON CONFLICT DO NOTHING;
    RETURN NEW;
END;
$$;
CREATE TRIGGER initialize_role_permissions AFTER INSERT ON sys.role
FOR EACH ROW EXECUTE FUNCTION sys.initialize_role_permissions();

-- Revoke shared sessions in the same transaction as relationship changes on either node.
CREATE OR REPLACE FUNCTION sys.revoke_relationship_sessions() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        UPDATE sys.login_session SET revoked_at=CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul'
        WHERE user_id=OLD.user_id AND revoked_at IS NULL;
    END IF;
    IF TG_OP <> 'DELETE' THEN
        UPDATE sys.login_session SET revoked_at=CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul'
        WHERE user_id=NEW.user_id AND revoked_at IS NULL;
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER revoke_user_role_sessions AFTER INSERT OR UPDATE OR DELETE ON sys.user_role
FOR EACH ROW EXECUTE FUNCTION sys.revoke_relationship_sessions();
CREATE TRIGGER revoke_user_part_sessions AFTER INSERT OR UPDATE OR DELETE ON sys.user_part
FOR EACH ROW EXECUTE FUNCTION sys.revoke_relationship_sessions();
CREATE TRIGGER revoke_user_project_sessions AFTER INSERT OR UPDATE OR DELETE ON sys.user_prj
FOR EACH ROW EXECUTE FUNCTION sys.revoke_relationship_sessions();

CREATE OR REPLACE FUNCTION sys.revoke_account_sessions() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE sys.login_session SET revoked_at=CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul'
    WHERE user_id=NEW.user_id AND revoked_at IS NULL;
    RETURN NEW;
END;
$$;
CREATE TRIGGER revoke_account_sessions AFTER UPDATE OF pswd_hash,user_stts_cd,rsgn_dt,dev_id,bsn_part_id,mfa_yn ON sys.user_info
FOR EACH ROW WHEN (OLD.pswd_hash IS DISTINCT FROM NEW.pswd_hash OR OLD.user_stts_cd IS DISTINCT FROM NEW.user_stts_cd
    OR OLD.rsgn_dt IS DISTINCT FROM NEW.rsgn_dt OR OLD.dev_id IS DISTINCT FROM NEW.dev_id
    OR OLD.bsn_part_id IS DISTINCT FROM NEW.bsn_part_id OR OLD.mfa_yn IS DISTINCT FROM NEW.mfa_yn)
EXECUTE FUNCTION sys.revoke_account_sessions();

CREATE OR REPLACE FUNCTION sys.revoke_role_sessions() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE sys.login_session s SET revoked_at=CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul'
    WHERE s.revoked_at IS NULL AND EXISTS (SELECT 1 FROM sys.user_role ur
        WHERE ur.user_id=s.user_id AND ur.role_id=OLD.role_id);
    RETURN NULL;
END;
$$;
CREATE TRIGGER revoke_role_sessions AFTER UPDATE OR DELETE ON sys.role
FOR EACH ROW EXECUTE FUNCTION sys.revoke_role_sessions();
CREATE TRIGGER revoke_permission_sessions AFTER UPDATE OR DELETE ON sys.role_menu_auth
FOR EACH ROW EXECUTE FUNCTION sys.revoke_role_sessions();
CREATE OR REPLACE FUNCTION sys.revoke_menu_sessions() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE sys.login_session SET revoked_at=CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Seoul' WHERE revoked_at IS NULL;
    RETURN NEW;
END;
$$;
CREATE TRIGGER revoke_menu_sessions AFTER UPDATE OF use_yn,upr_menu_id ON sys.menu
FOR EACH ROW WHEN (OLD.use_yn IS DISTINCT FROM NEW.use_yn OR OLD.upr_menu_id IS DISTINCT FROM NEW.upr_menu_id)
EXECUTE FUNCTION sys.revoke_menu_sessions();
