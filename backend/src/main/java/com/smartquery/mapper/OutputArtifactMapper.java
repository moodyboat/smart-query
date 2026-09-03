package com.smartquery.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartquery.entity.OutputArtifact;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OutputArtifactMapper extends BaseMapper<OutputArtifact> {
    @Select("SELECT * FROM sq_output_artifact WHERE id = #{id} FOR UPDATE")
    OutputArtifact selectForUpdate(@Param("id") Long id);
}
