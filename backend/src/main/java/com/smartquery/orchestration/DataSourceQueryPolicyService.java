package com.smartquery.orchestration;

import com.smartquery.common.BusinessException;
import com.smartquery.entity.DataSource;
import com.smartquery.mapper.DataSourceMapper;
import org.springframework.stereotype.Service;

/** Shared fail-closed authorization boundary for V2 data-source and agent runtimes. */
@Service
public class DataSourceQueryPolicyService {
    private final DataSourceMapper dataSourceMapper;

    public DataSourceQueryPolicyService(DataSourceMapper dataSourceMapper) {
        this.dataSourceMapper = dataSourceMapper;
    }

    public DataSource requireQueryable(Long id) {
        DataSource dataSource = id == null ? null : dataSourceMapper.selectById(id);
        if (dataSource == null || Integer.valueOf(1).equals(dataSource.getDeleted())) {
            throw new BusinessException(404, "数据源不存在: " + id);
        }
        if (dataSource.getStatus() != null && !Integer.valueOf(1).equals(dataSource.getStatus())) {
            throw new BusinessException(422, "数据源未启用: " + id);
        }
        if (Boolean.FALSE.equals(dataSource.getForQuestionAnswering())) {
            throw new BusinessException(422, "数据源未授权用于查询编排: " + id);
        }
        return dataSource;
    }
}
