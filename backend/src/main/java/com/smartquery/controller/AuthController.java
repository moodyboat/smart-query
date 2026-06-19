package com.smartquery.controller;

import com.smartquery.common.Result;
import com.smartquery.common.UserContextHolder;
import com.smartquery.dto.ChangePasswordRequest;
import com.smartquery.dto.LoginRequest;
import com.smartquery.dto.LoginResponse;
import com.smartquery.dto.UserInfo;
import com.smartquery.service.AuthService;
import com.smartquery.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final UserService userService;

    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.ok(authService.login(request));
    }

    @GetMapping("/me")
    public Result<UserInfo> me() {
        return Result.ok(authService.currentUser(UserContextHolder.getUserId()));
    }

    @PostMapping("/logout")
    public Result<Void> logout() {
        // JWT 无状态：前端清除本地 token 即完成登出
        return Result.ok();
    }

    @PostMapping("/password")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        userService.changeOwnPassword(request);
        return Result.ok();
    }
}
