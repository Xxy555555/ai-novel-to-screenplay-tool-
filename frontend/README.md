# ScriptForge 前端骨架

Vue3 + Vite + Pinia + Vue Router + Naive UI（深色主题）。

## 运行
```bash
npm install
npm run dev      # http://localhost:5173
npm run build    # 产物输出 dist/
```

## 结构
```
src/
├─ main.js            应用入口（注册 pinia / router / naive-ui）
├─ App.vue            外壳布局（顶栏 + router-view + 主题）
├─ router/            路由骨架
├─ stores/            Pinia 状态骨架
├─ api/http.js        Axios 基础实例 + 健康探测
├─ styles/tokens.css  设计令牌（深色主题变量）
└─ views/HomeView.vue 占位首页（验证前后端联通）
```

> 业务页面（P1–P9，见 `../docs/UI-Prototype-Design.md`）实现时在 `views/` 与 `components/` 内扩展。
