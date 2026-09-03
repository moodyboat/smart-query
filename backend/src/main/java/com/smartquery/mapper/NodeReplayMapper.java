package com.smartquery.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartquery.entity.NodeReplay;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface NodeReplayMapper extends BaseMapper<NodeReplay> {
    @Select("SELECT * FROM sq_node_replay WHERE id = #{id} FOR UPDATE")
    NodeReplay selectForUpdate(@Param("id") Long id);
}
