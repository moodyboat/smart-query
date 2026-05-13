package com.smartquery.controller;

import com.smartquery.common.Result;
import com.smartquery.entity.LlmConfigEntity;
import com.smartquery.mapper.LlmConfigMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/llm-config")
@RequiredArgsConstructor
public class LlmConfigController {

    private final LlmConfigMapper llmConfigMapper;

    @PostMapping
    public Result<LlmConfigEntity> create(@RequestBody LlmConfigEntity config) {
        llmConfigMapper.insert(config);
        return Result.ok(config);
    }

    @GetMapping
    public Result<List<LlmConfigEntity>> list() {
        return Result.ok(llmConfigMapper.selectList(null));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody LlmConfigEntity config) {
        config.setId(id);
        llmConfigMapper.updateById(config);
        return Result.ok(null);
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        llmConfigMapper.deleteById(id);
        return Result.ok(null);
    }
}
