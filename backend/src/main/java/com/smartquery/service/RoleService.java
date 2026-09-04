package com.smartquery.service;

import com.smartquery.common.BusinessException;
import com.smartquery.common.PermissionCodes;
import com.smartquery.common.UserContextHolder;
import com.smartquery.dto.PermissionInfo;
import com.smartquery.dto.RoleInfo;
import com.smartquery.dto.RoleUpsertRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Database-backed role and permission catalog. */
@Service
@RequiredArgsConstructor
public class RoleService {

    private final JdbcTemplate jdbcTemplate;

    public List<RoleInfo> listRoles(boolean includeDisabled) {
        String where = includeDisabled ? "r.deleted = 0" : "r.deleted = 0 AND r.enabled = 1";
        String sql = """
            SELECT r.id, r.code, r.name, r.description, r.enabled, r.system_role,
                   r.default_role, r.sort_order
            FROM sq_role r
            WHERE %s
            ORDER BY r.sort_order ASC, r.id ASC
            """.formatted(where);
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
                String code = rs.getString("code");
                List<PermissionInfo> permissions = permissionsForRole(code);
                return new RoleInfo(rs.getLong("id"), code, rs.getString("name"),
                    rs.getString("description"), rs.getInt("enabled"), rs.getInt("system_role"),
                    rs.getInt("default_role"), rs.getInt("sort_order"),
                    permissions.stream().map(PermissionInfo::code).toList(),
                    permissions.stream().map(PermissionInfo::name).toList());
            });
    }

    public List<PermissionInfo> listPermissions() {
        return jdbcTemplate.query("""
            SELECT id, code, name, description, module_name, sort_order
            FROM sq_permission
            WHERE enabled = 1 AND deleted = 0
            ORDER BY module_name ASC, sort_order ASC, id ASC
            """, (rs, rowNum) -> new PermissionInfo(rs.getLong("id"), rs.getString("code"),
            rs.getString("name"), rs.getString("description"), rs.getString("module_name"),
            rs.getInt("sort_order")));
    }

    public List<PermissionInfo> permissionsForRole(String roleCode) {
        if (roleCode == null || roleCode.isBlank()) return List.of();
        return jdbcTemplate.query("""
            SELECT p.id, p.code, p.name, p.description, p.module_name, p.sort_order
            FROM sq_permission p
            JOIN sq_role_permission rp ON rp.permission_id = p.id
            JOIN sq_role r ON r.id = rp.role_id
            WHERE r.code = ? AND r.deleted = 0
              AND p.enabled = 1 AND p.deleted = 0
            ORDER BY p.module_name ASC, p.sort_order ASC, p.id ASC
            """, (rs, rowNum) -> new PermissionInfo(rs.getLong("id"), rs.getString("code"),
            rs.getString("name"), rs.getString("description"), rs.getString("module_name"),
            rs.getInt("sort_order")), normalize(roleCode));
    }

    public List<String> permissionCodes(String roleCode) {
        return permissionsForRole(roleCode).stream().map(PermissionInfo::code).toList();
    }

    public boolean hasPermission(String roleCode, String permissionCode) {
        if (roleCode == null || roleCode.isBlank() || permissionCode == null || permissionCode.isBlank()) {
            return false;
        }
        Integer count = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM sq_role r
            JOIN sq_role_permission rp ON rp.role_id = r.id
            JOIN sq_permission p ON p.id = rp.permission_id
            WHERE r.code = ? AND r.enabled = 1 AND r.deleted = 0
              AND p.code = ? AND p.enabled = 1 AND p.deleted = 0
            """, Integer.class, normalize(roleCode), permissionCode);
        return count != null && count > 0;
    }

    public boolean currentUserHas(String permissionCode) {
        UserContextHolder.UserContext context = UserContextHolder.get();
        return context != null && hasPermission(context.role(), permissionCode);
    }

    public void requireCurrentUser(String permissionCode, String message) {
        if (!currentUserHas(permissionCode)) throw new BusinessException(403, message);
    }

    public void requireCurrentUserAny(String message, String... permissionCodes) {
        for (String permissionCode : permissionCodes) {
            if (currentUserHas(permissionCode)) return;
        }
        throw new BusinessException(403, message);
    }

    public String defaultRoleCode() {
        List<String> roles = jdbcTemplate.query("""
            SELECT code FROM sq_role
            WHERE default_role = 1 AND enabled = 1 AND deleted = 0
            ORDER BY sort_order ASC, id ASC
            """, (rs, rowNum) -> rs.getString("code"));
        if (!roles.isEmpty()) return roles.get(0);
        roles = jdbcTemplate.query("""
            SELECT code FROM sq_role WHERE enabled = 1 AND deleted = 0 ORDER BY sort_order ASC, id ASC
            """, (rs, rowNum) -> rs.getString("code"));
        if (roles.isEmpty()) throw new IllegalStateException("角色目录为空，无法确定默认角色");
        return roles.get(0);
    }

    public String firstRoleWithPermission(String permissionCode) {
        List<String> roles = jdbcTemplate.query("""
            SELECT r.code
            FROM sq_role r
            JOIN sq_role_permission rp ON rp.role_id = r.id
            JOIN sq_permission p ON p.id = rp.permission_id
            WHERE p.code = ? AND r.enabled = 1 AND r.deleted = 0
              AND p.enabled = 1 AND p.deleted = 0
            ORDER BY r.sort_order ASC, r.id ASC
            """, (rs, rowNum) -> rs.getString("code"), permissionCode);
        if (roles.isEmpty()) throw new IllegalStateException("没有启用的角色具备权限: " + permissionCode);
        return roles.get(0);
    }

    public String validateEnabledRole(String rawRole) {
        String role = normalize(rawRole);
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM sq_role WHERE code = ? AND enabled = 1 AND deleted = 0",
            Integer.class, role);
        if (count == null || count == 0) throw new BusinessException(422, "角色不存在或已停用: " + role);
        return role;
    }

    public String roleLabel(String roleCode) {
        if (roleCode == null || roleCode.isBlank()) return roleCode;
        List<String> labels = jdbcTemplate.query(
            "SELECT name FROM sq_role WHERE code = ? AND deleted = 0",
            (rs, rowNum) -> rs.getString("name"), normalize(roleCode));
        return labels.isEmpty() ? roleCode : labels.get(0);
    }

    public long enabledUserCountWithPermission(String permissionCode) {
        Long count = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM sq_user u
            JOIN sq_role r ON r.code = u.role
            JOIN sq_role_permission rp ON rp.role_id = r.id
            JOIN sq_permission p ON p.id = rp.permission_id
            WHERE p.code = ? AND u.enabled = 1 AND u.deleted = 0
              AND r.enabled = 1 AND r.deleted = 0 AND p.enabled = 1 AND p.deleted = 0
            """, Long.class, permissionCode);
        return count == null ? 0 : count;
    }

    @Transactional
    public RoleInfo create(RoleUpsertRequest request) {
        String code = normalize(request.value());
        String label = requiredLabel(request.label());
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM sq_role WHERE code = ? AND deleted = 0", Integer.class, code);
        if (count != null && count > 0) throw new BusinessException(409, "角色编码已存在: " + code);
        boolean makeDefault = Integer.valueOf(1).equals(request.defaultRole());
        if (makeDefault) jdbcTemplate.update("UPDATE sq_role SET default_role = 0 WHERE deleted = 0");
        jdbcTemplate.update("""
            INSERT INTO sq_role
              (code, name, description, enabled, system_role, default_role, sort_order,
               created_at, updated_at, deleted)
            VALUES (?, ?, ?, ?, 0, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
            """, code, label, trimToNull(request.description()),
            normalizeFlag(request.enabled(), 1), makeDefault ? 1 : 0,
            request.sortOrder() == null ? 100 : request.sortOrder());
        replacePermissions(code, request.permissions());
        return findByCode(code);
    }

    @Transactional
    public RoleInfo update(Long id, RoleUpsertRequest request) {
        RoleInfo existing = findById(id);
        if (existing == null) throw new BusinessException(404, "角色不存在: " + id);
        if (!normalize(request.value()).equals(existing.value())) {
            throw new BusinessException(422, "角色编码创建后不可修改");
        }
        int enabled = normalizeFlag(request.enabled(), existing.enabled());
        long assignedUsers = assignedUserCount(existing.value(), false);
        if (enabled == 0 && assignedUsers > 0) {
            throw new BusinessException(409, "角色仍有用户，重新分配后才能停用");
        }
        boolean makeDefault = Integer.valueOf(1).equals(request.defaultRole());
        if (existing.defaultRole() == 1 && enabled == 0) {
            throw new BusinessException(409, "默认角色不能停用，请先指定新的默认角色");
        }
        Set<String> requestedPermissions = new LinkedHashSet<>(
            request.permissions() == null ? List.of() : request.permissions());
        protectLastManager(existing, requestedPermissions);
        if (makeDefault) jdbcTemplate.update("UPDATE sq_role SET default_role = 0 WHERE deleted = 0");
        jdbcTemplate.update("""
            UPDATE sq_role
            SET name = ?, description = ?, enabled = ?, default_role = ?, sort_order = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ? AND deleted = 0
            """, requiredLabel(request.label()), trimToNull(request.description()), enabled,
            makeDefault ? 1 : existing.defaultRole(),
            request.sortOrder() == null ? existing.sortOrder() : request.sortOrder(), id);
        replacePermissions(existing.value(), List.copyOf(requestedPermissions));
        return findById(id);
    }

    @Transactional
    public void delete(Long id) {
        RoleInfo role = findById(id);
        if (role == null) return;
        if (assignedUserCount(role.value(), false) > 0) {
            throw new BusinessException(409, "角色仍有用户，不能删除");
        }
        if (role.defaultRole() == 1) throw new BusinessException(409, "默认角色不能删除");
        jdbcTemplate.update("DELETE FROM sq_role_permission WHERE role_id = ?", id);
        jdbcTemplate.update("DELETE FROM sq_role_scenario WHERE role = ?", role.value());
        jdbcTemplate.update("UPDATE sq_role SET deleted = 1, enabled = 0, updated_at = CURRENT_TIMESTAMP WHERE id = ?", id);
    }

    private RoleInfo findByCode(String code) {
        return listRoles(true).stream().filter(role -> role.value().equals(code)).findFirst().orElse(null);
    }

    private RoleInfo findById(Long id) {
        return listRoles(true).stream().filter(role -> role.id().equals(id)).findFirst().orElse(null);
    }

    private void replacePermissions(String roleCode, List<String> requested) {
        Long roleId = jdbcTemplate.queryForObject(
            "SELECT id FROM sq_role WHERE code = ? AND deleted = 0", Long.class, roleCode);
        jdbcTemplate.update("DELETE FROM sq_role_permission WHERE role_id = ?", roleId);
        Set<String> codes = new LinkedHashSet<>(requested == null ? List.of() : requested);
        for (String code : codes) {
            List<Long> permissionIds = jdbcTemplate.query(
                "SELECT id FROM sq_permission WHERE code = ? AND enabled = 1 AND deleted = 0",
                (rs, rowNum) -> rs.getLong("id"), code);
            if (permissionIds.isEmpty()) throw new BusinessException(422, "权限不存在或已停用: " + code);
            jdbcTemplate.update(
                "INSERT INTO sq_role_permission (role_id, permission_id, created_at) VALUES (?, ?, CURRENT_TIMESTAMP)",
                roleId, permissionIds.get(0));
        }
    }

    private void protectLastManager(RoleInfo existing, Set<String> requestedPermissions) {
        protectLastManagerPermission(existing, requestedPermissions, PermissionCodes.USER_MANAGE, "用户管理");
        protectLastManagerPermission(existing, requestedPermissions, PermissionCodes.ROLE_MANAGE, "角色管理");
    }

    private void protectLastManagerPermission(RoleInfo existing, Set<String> requestedPermissions,
                                                String permissionCode, String permissionName) {
        if (!existing.permissions().contains(permissionCode) || requestedPermissions.contains(permissionCode)) return;
        long assignedEnabledUsers = assignedUserCount(existing.value(), true);
        if (assignedEnabledUsers > 0
                && enabledUserCountWithPermission(permissionCode) <= assignedEnabledUsers) {
            throw new BusinessException(409, "不能移除最后一个可用账号的" + permissionName + "权限");
        }
    }

    private long assignedUserCount(String roleCode, boolean enabledOnly) {
        String enabled = enabledOnly ? " AND enabled = 1" : "";
        Long count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM sq_user WHERE role = ? AND deleted = 0" + enabled,
            Long.class, roleCode);
        return count == null ? 0 : count;
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) throw new BusinessException(422, "角色编码不能为空");
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private int normalizeFlag(Integer value, int fallback) {
        if (value == null) return fallback;
        return value == 0 ? 0 : 1;
    }

    private String requiredLabel(String value) {
        if (value == null || value.isBlank()) throw new BusinessException(422, "角色名称不能为空");
        String label = value.trim();
        if (label.length() > 80) throw new BusinessException(422, "角色名称不能超过80字符");
        return label;
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
