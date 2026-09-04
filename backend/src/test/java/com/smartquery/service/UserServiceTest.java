package com.smartquery.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.smartquery.common.BusinessException;
import com.smartquery.common.PermissionCodes;
import com.smartquery.common.UserContextHolder;
import com.smartquery.support.TestRoles;
import com.smartquery.dto.CreateUserRequest;
import com.smartquery.dto.UpdateUserRequest;
import com.smartquery.dto.UserInfo;
import com.smartquery.entity.User;
import com.smartquery.mapper.UserMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock private UserMapper users;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private RoleService roleService;
    private UserService service;

    @BeforeAll
    static void initializeMyBatisMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), User.class);
    }

    @BeforeEach
    void setUp() {
        service = new UserService(users, passwordEncoder, roleService);
        UserContextHolder.set(new UserContextHolder.UserContext(1L, "admin", TestRoles.ADMIN));
        lenient().when(roleService.validateEnabledRole(TestRoles.OPERATOR_REVIEWER)).thenReturn(TestRoles.OPERATOR_REVIEWER);
        lenient().when(roleService.validateEnabledRole(TestRoles.USER)).thenReturn(TestRoles.USER);
        lenient().when(roleService.defaultRoleCode()).thenReturn(TestRoles.USER);
        lenient().when(roleService.permissionCodes(any())).thenReturn(List.of());
    }

    @AfterEach
    void clearContext() {
        UserContextHolder.clear();
    }

    @Test
    void createsOperatorReviewerAsEnabledAccount() {
        when(users.selectCount(any())).thenReturn(0L);
        when(passwordEncoder.encode("Review123!" )).thenReturn("encoded");
        CreateUserRequest request = request("reviewer", "Review123!", TestRoles.OPERATOR_REVIEWER);

        UserInfo created = service.create(request);

        ArgumentCaptor<User> inserted = ArgumentCaptor.forClass(User.class);
        verify(users).insert(inserted.capture());
        assertEquals(TestRoles.OPERATOR_REVIEWER, inserted.getValue().getRole());
        assertEquals(1, inserted.getValue().getEnabled());
        assertEquals(1, created.getEnabled());
    }

    @Test
    void defaultsBlankRoleToBusinessUser() {
        when(users.selectCount(any())).thenReturn(0L);
        when(passwordEncoder.encode("Analyst123!" )).thenReturn("encoded");
        CreateUserRequest request = request("analyst", "Analyst123!", " ");

        assertEquals(TestRoles.USER, service.create(request).getRole());
    }

    @Test
    void rejectsUnknownRole() {
        CreateUserRequest request = request("unknown", "Unknown123!", "super_admin");
        doThrow(new BusinessException(422, "角色不存在或已停用"))
            .when(roleService).validateEnabledRole("super_admin");
        assertThrows(BusinessException.class, () -> service.create(request));
    }

    @Test
    void userListIncludesEnabledState() {
        User reviewer = new User();
        reviewer.setId(2L);
        reviewer.setUsername("reviewer");
        reviewer.setRole(TestRoles.OPERATOR_REVIEWER);
        reviewer.setEnabled(1);
        when(users.selectList(any())).thenReturn(List.of(reviewer));

        List<UserInfo> result = service.list(null);

        assertEquals(1, result.size());
        assertEquals(1, result.get(0).getEnabled());
    }

    @Test
    void cannotDemoteLastEnabledAdministrator() {
        User admin = new User();
        admin.setId(1L);
        admin.setRole(TestRoles.ADMIN);
        admin.setEnabled(1);
        when(users.selectById(1L)).thenReturn(admin);
        when(roleService.hasPermission(TestRoles.ADMIN, PermissionCodes.USER_MANAGE)).thenReturn(true);
        when(roleService.hasPermission(TestRoles.USER, PermissionCodes.USER_MANAGE)).thenReturn(false);
        when(roleService.enabledUserCountWithPermission(PermissionCodes.USER_MANAGE)).thenReturn(1L);
        UpdateUserRequest request = new UpdateUserRequest();
        request.setRole(TestRoles.USER);

        assertThrows(BusinessException.class, () -> service.update(1L, request));
    }

    private CreateUserRequest request(String username, String password, String role) {
        CreateUserRequest request = new CreateUserRequest();
        request.setUsername(username);
        request.setPassword(password);
        request.setDisplayName(username);
        request.setRole(role);
        return request;
    }
}
