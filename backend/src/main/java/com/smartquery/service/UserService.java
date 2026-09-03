package com.smartquery.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartquery.common.BusinessException;
import com.smartquery.common.UserContextHolder;
import com.smartquery.common.UserRoles;
import com.smartquery.dto.ChangePasswordRequest;
import com.smartquery.dto.CreateUserRequest;
import com.smartquery.dto.UpdateUserRequest;
import com.smartquery.dto.UserInfo;
import com.smartquery.entity.User;
import com.smartquery.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    /** 仅 admin 可调用用户管理接口（同包/外部 Controller 共用） */
    public void requireAdmin() {
        UserContextHolder.UserContext ctx = UserContextHolder.get();
        if (ctx == null || !UserRoles.ADMIN.equals(ctx.role())) {
            throw new BusinessException(403, "无权限，仅管理员可操作");
        }
    }

    public List<UserInfo> list(String keyword) {
        requireAdmin();
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<User>().orderByDesc(User::getCreatedAt);
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like(User::getUsername, keyword)
                .or().like(User::getDisplayName, keyword));
        }
        return userMapper.selectList(wrapper).stream().map(this::toUserInfo).toList();
    }

    public UserInfo create(CreateUserRequest request) {
        requireAdmin();
        if (request.getRole() == null || request.getRole().isBlank()) {
            request.setRole(UserRoles.USER);
        }
        request.setRole(supportedRole(request.getRole()));
        Long existing = userMapper.selectCount(new LambdaQueryWrapper<User>()
            .eq(User::getUsername, request.getUsername()));
        if (existing != null && existing > 0) {
            throw new BusinessException(409, "用户名已存在");
        }
        User user = new User();
        user.setUsername(request.getUsername());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setDisplayName(request.getDisplayName());
        user.setEmail(request.getEmail());
        user.setRole(request.getRole());
        user.setEnabled(1);
        userMapper.insert(user);
        return toUserInfo(user);
    }

    public UserInfo update(Long id, UpdateUserRequest request) {
        requireAdmin();
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        String nextRole = request.getRole() == null || request.getRole().isBlank()
            ? user.getRole() : supportedRole(request.getRole());
        int nextEnabled = request.getEnabled() == null
            ? (user.getEnabled() == null ? 1 : user.getEnabled()) : request.getEnabled();
        if (UserRoles.ADMIN.equals(user.getRole())
                && (!UserRoles.ADMIN.equals(nextRole) || nextEnabled == 0)) {
            Long adminCount = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getRole, UserRoles.ADMIN).eq(User::getEnabled, 1));
            if (adminCount != null && adminCount <= 1) {
                throw new BusinessException(400, "不能禁用或降级最后一个管理员");
            }
        }
        if (request.getDisplayName() != null) user.setDisplayName(request.getDisplayName());
        if (request.getEmail() != null) user.setEmail(request.getEmail());
        user.setRole(nextRole);
        if (request.getEnabled() != null) {
            user.setEnabled(request.getEnabled());
        }
        userMapper.updateById(user);
        return toUserInfo(user);
    }

    public void resetPassword(Long id, String newPassword) {
        requireAdmin();
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        if (newPassword == null || newPassword.length() < 6) {
            throw new BusinessException(400, "新密码长度至少 6 位");
        }
        User update = new User();
        update.setId(id);
        update.setPasswordHash(passwordEncoder.encode(newPassword));
        userMapper.updateById(update);
    }

    public void changeOwnPassword(ChangePasswordRequest request) {
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            throw new BusinessException(401, "未登录");
        }
        User user = userMapper.selectById(userId);
        if (user == null || !passwordEncoder.matches(request.getOldPassword(), user.getPasswordHash())) {
            throw new BusinessException(400, "原密码错误");
        }
        User update = new User();
        update.setId(userId);
        update.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userMapper.updateById(update);
    }

    public void delete(Long id) {
        requireAdmin();
        User user = userMapper.selectById(id);
        if (user == null) {
            return;
        }
        if (UserRoles.ADMIN.equals(user.getRole())) {
            Long adminCount = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getRole, UserRoles.ADMIN));
            if (adminCount != null && adminCount <= 1) {
                throw new BusinessException(400, "不能删除最后一个管理员");
            }
        }
        // 不允许删除自己
        if (id.equals(UserContextHolder.getUserId())) {
            throw new BusinessException(400, "不能删除当前登录账号");
        }
        userMapper.deleteById(id);
    }

    private UserInfo toUserInfo(User user) {
        return new UserInfo(user.getId(), user.getUsername(), user.getDisplayName(), user.getEmail(), user.getRole());
    }

    private String supportedRole(String raw) {
        String role = raw == null ? UserRoles.USER : raw.trim().toLowerCase(Locale.ROOT);
        if (!UserRoles.isSupported(role)) throw new BusinessException(422, "不支持的用户角色: " + role);
        return role;
    }
}
