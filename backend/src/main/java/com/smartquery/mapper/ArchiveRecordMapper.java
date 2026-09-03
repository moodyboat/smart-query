package com.smartquery.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartquery.entity.ArchiveRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ArchiveRecordMapper extends BaseMapper<ArchiveRecord> {
    @Select("SELECT * FROM sq_archive_record WHERE id = #{id} FOR UPDATE")
    ArchiveRecord selectForUpdate(@Param("id") Long id);
}
