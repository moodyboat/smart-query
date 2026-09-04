package com.smartquery.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class UserInfo {

    private Long id;
    private String username;
    private String displayName;
    private String email;
    private String role;
    private String roleLabel;
    private List<String> permissions;
    private Integer enabled;
}
