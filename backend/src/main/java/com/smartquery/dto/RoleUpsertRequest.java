package com.smartquery.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record RoleUpsertRequest(
    @NotBlank(message = "角色编码不能为空")
    @Pattern(regexp = "^[a-z][a-z0-9_.-]{2,63}$", message = "角色编码只能包含小写字母、数字、点、横线和下划线")
    String value,
    @NotBlank(message = "角色名称不能为空")
    @Size(max = 80, message = "角色名称最长 80 个字符")
    String label,
    @Size(max = 500, message = "角色说明最长 500 个字符")
    String description,
    Integer enabled,
    Integer defaultRole,
    Integer sortOrder,
    List<String> permissions
) {
}
