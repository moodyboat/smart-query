package com.smartquery.dto;

import java.util.List;

/** Database-backed role definition returned to the platform configuration UI. */
public record RoleInfo(
    Long id,
    String value,
    String label,
    String description,
    Integer enabled,
    Integer systemRole,
    Integer defaultRole,
    Integer sortOrder,
    List<String> permissions,
    List<String> capabilities
) {
}
