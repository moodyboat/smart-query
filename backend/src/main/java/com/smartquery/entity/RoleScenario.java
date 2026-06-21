package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 角色-场景授权关联实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sq_role_scenario")
public class RoleScenario extends BaseEntity {

    private String role;

    private Long scenarioId;
}
