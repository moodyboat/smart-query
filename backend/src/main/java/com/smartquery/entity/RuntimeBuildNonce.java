package com.smartquery.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** Persisted HMAC nonce so worker requests cannot be replayed across application instances. */
@Data
@TableName("sq_runtime_build_nonce")
public class RuntimeBuildNonce {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String nonceHash;
    private LocalDateTime expiresAt;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
