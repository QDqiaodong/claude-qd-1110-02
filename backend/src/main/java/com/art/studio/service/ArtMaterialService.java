package com.art.studio.service;

import java.time.LocalDateTime;
import java.util.List;

import com.art.studio.common.BizException;
import com.art.studio.common.Dict;
import com.art.studio.entity.ArtMaterial;
import com.art.studio.repository.ArtMaterialRepository;

import org.springframework.stereotype.Service;

@Service
public class ArtMaterialService {

    private final ArtMaterialRepository repo;

    public ArtMaterialService(ArtMaterialRepository repo) {
        this.repo = repo;
    }

    public List<ArtMaterial> list(String category, String status) {
        if (category != null && status != null) {
            return repo.findByCategoryAndStatus(category, status);
        }
        if (category != null) {
            return repo.findByCategory(category);
        }
        if (status != null) {
            return repo.findByStatus(status);
        }
        return repo.findAll();
    }

    public ArtMaterial create(ArtMaterial input) {
        if (input.name == null || input.name.isBlank()) {
            throw new BizException("画材名称不能为空");
        }
        if (repo.findByName(input.name).isPresent()) {
            throw new BizException("画材名称已存在：" + input.name);
        }
        if (input.stock == null || input.stock < 0) {
            throw new BizException("库存不能为负");
        }
        String category = input.category == null ? Dict.MATERIAL_CATEGORIES.get(0) : input.category;
        if (!Dict.isValidMaterialCategory(category)) {
            throw new BizException("画材类别不合法：" + category);
        }
        String status = input.status == null ? "在库" : input.status;
        if (!Dict.isValidMaterialStatus(status)) {
            throw new BizException("画材状态不合法：" + status);
        }
        ArtMaterial m = new ArtMaterial();
        m.name = input.name;
        m.category = category;
        m.stock = input.stock;
        m.unit = input.unit == null ? "支" : input.unit;
        m.status = status;
        m.createdAt = LocalDateTime.now();
        return repo.save(m);
    }

    public ArtMaterial update(Long id, ArtMaterial input) {
        ArtMaterial m = repo.findById(id).orElseThrow(() -> new BizException("画材不存在：" + id));
        if (input.name != null) {
            if (!input.name.equals(m.name) && repo.findByName(input.name).isPresent()) {
                throw new BizException("画材名称已存在：" + input.name);
            }
            m.name = input.name;
        }
        if (input.category != null) {
            if (!Dict.isValidMaterialCategory(input.category)) {
                throw new BizException("画材类别不合法：" + input.category);
            }
            m.category = input.category;
        }
        if (input.stock != null) {
            if (input.stock < 0) {
                throw new BizException("库存不能为负");
            }
            m.stock = input.stock;
        }
        if (input.unit != null) {
            m.unit = input.unit;
        }
        if (input.status != null) {
            if (!Dict.isValidMaterialStatus(input.status)) {
                throw new BizException("画材状态不合法：" + input.status);
            }
            m.status = input.status;
        }
        return repo.save(m);
    }
}
