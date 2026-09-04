package com.smartquery.dto;

/** Assignable capability stored in the permission catalog. */
public record PermissionInfo(
    Long id,
    String code,
    String name,
    String description,
    String module,
    Integer sortOrder
) {
}
