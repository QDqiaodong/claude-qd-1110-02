<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'

const emit = defineEmits(['changed'])

const loading = ref(false)
const materials = ref([])
const category = ref('')
const status = ref('')

const CATS = ['颜料', '画笔', '画布', '工具']
const STATUS = ['在库', '停用']

const dialogVisible = ref(false)
const editing = ref(false)
const form = reactive({ id: null, name: '', category: '颜料', stock: 0, unit: '支', status: '在库' })

async function load() {
  loading.value = true
  try { materials.value = await api.listArtMaterials(category.value || null, status.value || null) }
  catch (e) { ElMessage.error(e.message) } finally { loading.value = false }
}

function resetFilters() { category.value = ''; status.value = ''; load() }

function addMaterial() {
  editing.value = false
  Object.assign(form, { id: null, name: '', category: '颜料', stock: 0, unit: '支', status: '在库' })
  dialogVisible.value = true
}
function editMaterial(row) {
  editing.value = true
  Object.assign(form, { id: row.id, name: row.name, category: row.category, stock: row.stock, unit: row.unit, status: row.status })
  dialogVisible.value = true
}
async function saveMaterial() {
  try {
    if (editing.value) await api.updateArtMaterial(form.id, { ...form })
    else await api.createArtMaterial({ ...form })
    ElMessage.success('已保存')
    dialogVisible.value = false
    await load(); emit('changed')
  } catch (e) { ElMessage.error(e.message) }
}
async function toggleStatus(row) {
  const next = row.status === '在库' ? '停用' : '在库'
  try { await api.updateArtMaterial(row.id, { status: next }); ElMessage.success(next === '停用' ? '已停用' : '已启用'); await load(); emit('changed') }
  catch (e) { ElMessage.error(e.message) }
}

const statusType = (s) => (s === '在库' ? 'success' : 'info')

onMounted(load)
</script>

<template>
  <div>
    <div class="toolbar">
      <div class="filters">
        <el-select v-model="category" placeholder="类别" clearable style="width:120px" @change="load">
          <el-option v-for="c in CATS" :key="c" :label="c" :value="c" />
        </el-select>
        <el-select v-model="status" placeholder="状态" clearable style="width:120px" @change="load">
          <el-option v-for="s in STATUS" :key="s" :label="s" :value="s" />
        </el-select>
        <el-button @click="resetFilters">重置</el-button>
      </div>
      <el-button type="primary" @click="addMaterial">新增画材</el-button>
    </div>

    <el-table :data="materials" v-loading="loading" border stripe>
      <el-table-column prop="name" label="画材名" />
      <el-table-column prop="category" label="类别" width="90">
        <template #default="{ row }"><el-tag>{{ row.category }}</el-tag></template>
      </el-table-column>
      <el-table-column label="库存" width="100">
        <template #default="{ row }">
          <span :class="{ 'low': row.stock <= 10 }">{{ row.stock }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="unit" label="单位" width="80" />
      <el-table-column prop="status" label="状态" width="100">
        <template #default="{ row }"><el-tag :type="statusType(row.status)">{{ row.status }}</el-tag></template>
      </el-table-column>
      <el-table-column label="操作" fixed="right" width="160">
        <template #default="{ row }">
          <el-button link type="primary" @click="editMaterial(row)">编辑</el-button>
          <el-button link :type="row.status === '在库' ? 'warning' : 'success'" @click="toggleStatus(row)">
            {{ row.status === '在库' ? '停用' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑画材' : '新增画材'" width="420px">
      <el-form label-width="72px">
        <el-form-item label="画材名"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="类别">
          <el-select v-model="form.category"><el-option v-for="c in CATS" :key="c" :label="c" :value="c" /></el-select>
        </el-form-item>
        <el-form-item label="库存"><el-input-number v-model="form.stock" :min="0" /></el-form-item>
        <el-form-item label="单位"><el-input v-model="form.unit" /></el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status"><el-option v-for="s in STATUS" :key="s" :label="s" :value="s" /></el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveMaterial">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.filters { display: flex; gap: 8px; }
.low { color: #e06c75; font-weight: 700; }
</style>
