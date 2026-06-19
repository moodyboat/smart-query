package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sq_user")
public class User extends BaseEntity {

    private String username;

    @JsonIgnore
    private String passwordHash;

    private String displayName;

    private String email;

    private String role;

    private Integer enabled;

    private LocalDateTime lastLoginAt;
}
