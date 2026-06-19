package com.smartquery.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartquery.common.AuthenticationException;
import com.smartquery.config.JwtUtil;
import com.smartquery.dto.LoginRequest;
import com.smartquery.dto.LoginResponse;
import com.smartquery.dto.UserInfo;
import com.smartquery.entity.User;
import com.smartquery.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;

    public LoginResponse login(LoginRequest request) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
            .eq(User::getUsername, request.getUsername()));
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            // 用户名与密码错误合并为同一提示，避免账号枚举
            throw new AuthenticationException("用户名或密码错误");
        }
        if (user.getEnabled() == null || user.getEnabled() != 1) {
            throw new AuthenticationException("账号已被禁用，请联系管理员");
        }

        User update = new User();
        update.setId(user.getId());
        update.setLastLoginAt(LocalDateTime.now());
        userMapper.updateById(update);

        String token = jwtUtil.generate(user.getId(), user.getUsername(), user.getRole());
        UserInfo userInfo = toUserInfo(user);
        return new LoginResponse(token, "Bearer", jwtUtil.getExpirationSeconds(), userInfo);
    }

    public UserInfo currentUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new AuthenticationException("用户不存在");
        }
        return toUserInfo(user);
    }

    private UserInfo toUserInfo(User user) {
        return new UserInfo(user.getId(), user.getUsername(), user.getDisplayName(), user.getEmail(), user.getRole());
    }
}
