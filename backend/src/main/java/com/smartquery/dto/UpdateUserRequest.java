package com.smartquery.dto;

import lombok.Data;

@Data
public class UpdateUserRequest {

    private String displayName;
    private String email;
    private String role;
    private Integer enabled;
}
