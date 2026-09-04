package com.smartquery.controller;

import com.smartquery.common.Result;
import com.smartquery.common.PermissionCodes;
import com.smartquery.dto.CreateUserRequest;
import com.smartquery.dto.PermissionInfo;
import com.smartquery.dto.RoleInfo;
import com.smartquery.dto.RoleUpsertRequest;
import com.smartquery.dto.UpdateUserRequest;
import com.smartquery.dto.UserInfo;
import com.smartquery.service.UserService;
import com.smartquery.service.RoleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 用户、角色与权限目录管理；所有管理能力均由数据库权限授予。 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final RoleService roleService;

    @GetMapping
    public Result<List<UserInfo>> list(@RequestParam(required = false) String keyword) {
        return Result.ok(userService.list(keyword));
    }

    @GetMapping("/roles")
    public Result<List<RoleInfo>> roles() {
        roleService.requireCurrentUserAny("无权限查看角色目录",
            PermissionCodes.USER_MANAGE, PermissionCodes.ROLE_MANAGE, PermissionCodes.SCENARIO_MANAGE);
        return Result.ok(roleService.listRoles(true));
    }

    @GetMapping("/roles/permissions")
    public Result<List<PermissionInfo>> permissions() {
        roleService.requireCurrentUser(PermissionCodes.ROLE_MANAGE, "无权限查看角色权限目录");
        return Result.ok(roleService.listPermissions());
    }

    @PostMapping("/roles")
    public Result<RoleInfo> createRole(@Valid @RequestBody RoleUpsertRequest request) {
        roleService.requireCurrentUser(PermissionCodes.ROLE_MANAGE, "无权限新增角色");
        return Result.ok(roleService.create(request));
    }

    @PutMapping("/roles/{id}")
    public Result<RoleInfo> updateRole(@PathVariable Long id,
                                       @Valid @RequestBody RoleUpsertRequest request) {
        roleService.requireCurrentUser(PermissionCodes.ROLE_MANAGE, "无权限修改角色");
        return Result.ok(roleService.update(id, request));
    }

    @DeleteMapping("/roles/{id}")
    public Result<Void> deleteRole(@PathVariable Long id) {
        roleService.requireCurrentUser(PermissionCodes.ROLE_MANAGE, "无权限删除角色");
        roleService.delete(id);
        return Result.ok();
    }

    @PostMapping
    public Result<UserInfo> create(@Valid @RequestBody CreateUserRequest request) {
        return Result.ok(userService.create(request));
    }

    @PutMapping("/{id}")
    public Result<UserInfo> update(@PathVariable Long id, @RequestBody UpdateUserRequest request) {
        return Result.ok(userService.update(id, request));
    }

    @PutMapping("/{id}/password")
    public Result<Void> resetPassword(@PathVariable Long id, @RequestBody java.util.Map<String, String> body) {
        userService.resetPassword(id, body.get("newPassword"));
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        userService.delete(id);
        return Result.ok();
    }
}
