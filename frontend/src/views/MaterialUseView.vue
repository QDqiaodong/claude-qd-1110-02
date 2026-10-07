<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'

const emit = defineEmits(['changed'])

const loading = ref(false)
const uses = ref([])
const students = ref([])
const materials = ref([])
const studentMap = ref({})
const materialMap = ref({})

const dialogVisible = ref(false)
const form = reactive({ studentId: null, materialId: null, qty: 1, useDate: '' })
const selectedMaterial = ref(null)

async function loadStudents() {
  students.value = await api.listStudents()
  studentMap.value = Object.fromEntries(students.value.map((s) => [s.id, s.name]))
}
async function loadMaterials() {
  // 领用只涉及「在库」画材，但仍展示全部以便了解台账
  materials.value = await api.listArtMaterials()
  materialMap.value = Object.fromEntries(materials.value.map((m) => [m.id, m]))
}
async function load() {
  loading.value = true
  try { uses.value = await api.listMaterialUses() } catch (e) { ElMessage.error(e.message) } finally { loading.value = false }
}

function addUse() {
  Object.assign(form, { studentId: null, materialId: null, qty: 1, useDate: '' })
  selectedMaterial.value = null
  dialogVisible.value = true
}
function onMaterialChange(id) { selectedMaterial.value = materials.value.find((m) => m.id === id) || null }

async function saveUse() {
  try {
    await api.createMaterialUse({ ...form })
    ElMessage.success('领用成功，库存已扣减')
    dialogVisible.value = false
    await Promise.all([load(), loadMaterials()]); emit('changed')
  } catch (e) { ElMessage.error(e.message) }
}

onMounted(async () => { await Promise.all([loadStudents(), loadMaterials(), load()]) })
</script>

<template>
  <div>
    <div class="toolbar">
      <span class="hint">新建领用记录即扣减对应画材库存；停用或库存不足的画材会被拦截。</span>
      <el-button type="primary" @click="addUse">新增领用</el-button>
    </div>

    <el-table :data="uses" v-loading="loading" border stripe>
      <el-table-column label="学员" width="120">
        <template #default="{ row }">{{ studentMap[row.studentId] || row.studentId }}</template>
      </el-table-column>
      <el-table-column label="画材" min-width="150">
        <template #default="{ row }">{{ materialMap[row.materialId] ? materialMap[row.materialId].name : row.materialId }}</template>
      </el-table-column>
      <el-table-column prop="qty" label="数量" width="90" />
      <el-table-column prop="useDate" label="领用日期" width="130" />
    </el-table>

    <el-dialog v-model="dialogVisible" title="新增领用" width="420px">
      <el-form label-width="80px">
        <el-form-item label="学员">
          <el-select v-model="form.studentId" placeholder="选择学员" filterable style="width:100%">
            <el-option v-for="s in students" :key="s.id" :label="s.name + '（' + s.code + '）'" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="画材">
          <el-select v-model="form.materialId" placeholder="选择在库画材" filterable style="width:100%" @change="onMaterialChange">
            <el-option
              v-for="m in materials"
              :key="m.id"
              :label="m.name + '（剩 ' + m.stock + ' ' + m.unit + '，' + m.status + '）'"
              :value="m.id"
              :disabled="m.status !== '在库'"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="数量">
          <el-input-number v-model="form.qty" :min="1" />
          <span class="tip" v-if="selectedMaterial">
            剩余库存 {{ selectedMaterial.stock }} {{ selectedMaterial.unit }}
          </span>
        </el-form-item>
        <el-form-item label="日期">
          <el-date-picker v-model="form.useDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveUse">确认领用</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.hint { color: var(--muted); font-size: 13px; }
.tip { margin-left: 10px; color: var(--muted); font-size: 12px; }
</style>
