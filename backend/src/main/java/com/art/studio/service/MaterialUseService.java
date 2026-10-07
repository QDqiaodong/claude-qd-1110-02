package com.art.studio.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.art.studio.common.BizException;
import com.art.studio.entity.ArtMaterial;
import com.art.studio.entity.MaterialUse;
import com.art.studio.repository.ArtMaterialRepository;
import com.art.studio.repository.MaterialUseRepository;
import com.art.studio.repository.StudentRepository;

import org.springframework.stereotype.Service;

@Service
public class MaterialUseService {

    private final MaterialUseRepository repo;
    private final ArtMaterialRepository materialRepo;
    private final StudentRepository studentRepo;

    public MaterialUseService(MaterialUseRepository repo, ArtMaterialRepository materialRepo,
            StudentRepository studentRepo) {
        this.repo = repo;
        this.materialRepo = materialRepo;
        this.studentRepo = studentRepo;
    }

    public List<MaterialUse> list() {
        return repo.findAll();
    }

    /** 新建即扣库存；引用画材须「在库」，库存不足则拦截。 */
    public MaterialUse create(MaterialUse input) {
        if (input.studentId == null) {
            throw new BizException("领用必须指定学员");
        }
        studentRepo.findById(input.studentId)
                .orElseThrow(() -> new BizException("学员不存在：" + input.studentId));
        if (input.materialId == null) {
            throw new BizException("领用必须指定画材");
        }
        ArtMaterial m = materialRepo.findById(input.materialId)
                .orElseThrow(() -> new BizException("画材不存在：" + input.materialId));
        if (!"在库".equals(m.status)) {
            throw new BizException("画材「" + m.name + "」已停用，不可领用");
        }
        if (input.qty == null || input.qty <= 0) {
            throw new BizException("领用数量须大于 0");
        }
        if (m.stock < input.qty) {
            throw new BizException("画材「" + m.name + "」库存不足（剩余 " + m.stock + "）");
        }
        m.stock -= input.qty;
        materialRepo.save(m);

        MaterialUse u = new MaterialUse();
        u.studentId = input.studentId;
        u.materialId = input.materialId;
        u.qty = input.qty;
        u.useDate = input.useDate == null ? LocalDate.now() : input.useDate;
        u.createdAt = LocalDateTime.now();
        return repo.save(u);
    }
}
