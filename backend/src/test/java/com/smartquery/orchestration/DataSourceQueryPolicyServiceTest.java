package com.smartquery.orchestration;

import com.smartquery.common.BusinessException;
import com.smartquery.entity.DataSource;
import com.smartquery.mapper.DataSourceMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DataSourceQueryPolicyServiceTest {
    private final DataSourceMapper mapper = mock(DataSourceMapper.class);
    private final DataSourceQueryPolicyService service = new DataSourceQueryPolicyService(mapper);

    @Test
    void acceptsOnlyActiveQueryEnabledDataSource() {
        DataSource dataSource = new DataSource();
        dataSource.setId(8L);
        dataSource.setStatus(1);
        dataSource.setForQuestionAnswering(true);
        dataSource.setDeleted(0);
        when(mapper.selectById(8L)).thenReturn(dataSource);

        assertEquals(dataSource, service.requireQueryable(8L));
    }

    @Test
    void rejectsDataSourceExcludedFromQuerying() {
        DataSource dataSource = new DataSource();
        dataSource.setId(9L);
        dataSource.setStatus(1);
        dataSource.setForQuestionAnswering(false);
        dataSource.setDeleted(0);
        when(mapper.selectById(9L)).thenReturn(dataSource);

        assertThrows(BusinessException.class, () -> service.requireQueryable(9L));
    }
}
