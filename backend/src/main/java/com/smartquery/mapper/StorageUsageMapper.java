package com.smartquery.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartquery.entity.StorageUsage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface StorageUsageMapper extends BaseMapper<StorageUsage> {
    @Select("SELECT * FROM sq_storage_usage WHERE owner_user_id = #{ownerUserId} FOR UPDATE")
    StorageUsage selectForUpdate(@Param("ownerUserId") String ownerUserId);
}
