# MG Locker

> 📝 **作者：程宗杨** · © 2026 保留所有权利 · 采用 [MIT 许可证](LICENSE)

Android 电视锁机（Kiosk）应用：把电视锁定在一个全屏界面，防止小孩/客人随意退出，家长可通过手机浏览器或遥控器秘技远程解锁。

- 📦 包名：`com.mgxd.mglocker`
- 🎬 当前版本：**v2.4**（versionCode 24）
- 📺 适配：Android 6.0+（已在 TCL 深度定制 Android 6.0 电视上实机验证，无 root）
- 🛠️ 技术栈：Kotlin 2.3.10 · Jetpack Compose（BOM 2026.01.01）· AGP 9.0.0 · NanoHTTPD 2.3.1

## 核心能力

| 能力 | 说明 |
|------|------|
| 🔒 全屏锁定 | 锁定后 Home / 返回 / 菜单 / 设置等键均无法退出 |
| 🛡️ 双层锁定 | Lock Task 硬锁 + 设备管理员（防卸载/防强停）+ Home 自动弹回软锁 |
| 🏠 Home 弹回 | GuardService 前台服务 500ms 轮询拉起，Home 后打开任何 App 都会被锁回 |
| 💓 心跳保活 | HeartbeatReceiver 60 秒心跳闹钟，进程被杀后由系统强制复活并恢复锁定 |
| 📡 局域网控制台 | 8080 端口 Web 页面：查看状态 / 锁定 / 解锁（带确认弹窗 + 实时状态徽章） |
| 🎮 遥控器秘技 | 上上下下左左右右（Konami Code）一键解锁 |
| ⚡ 开机自启 | 电视重启后自动进入锁定 |
| 🩺 诊断模式 | 锁定失败的真实异常直接显示在锁定界面 |

## 项目结构

```
app/src/main/java/com/mgxd/mglocker/
├── MainActivity.kt        # 锁定界面 + 遥控器按键拦截 + Konami 秘技 + Home 弹回
├── GuardService.kt        # 前台守护服务：HTTP 服务器 + 轮询拉起锁定界面
├── LockerHttpServer.kt    # NanoHTTPD 局域网控制台（/lock /unlock /status）
├── AdminReceiver.kt       # 设备管理员（防卸载/防强停）
├── BootReceiver.kt        # 开机自启
└── ui/                    # Compose 主题
```

## 构建 & 部署

```bash
# 一键构建并导出到 Download（自动命名 mglocker<版本号>.apk）
bash export_apk.sh --no-daemon

# 版本迭代：修改 app/build.gradle.kts 中 versionName（+0.1）/ versionCode（+1）后重新构建
```

电视部署流程：推送 APK 到电视 Download → 当贝市场「APK 管理器」→ 遥控器安装。

## 自动构建（GitHub Actions）

| 触发 | 动作 |
|------|------|
| `push` 到 `main` | 自动构建 Debug APK → Actions 页面上传 Artifact |
| 打 `tag v*`（如 `v2.5`） | 自动构建 Release APK → 自动发布 GitHub Release |

手动触发：Actions 页面 → Workflow → Run workflow。

## 局域网控制台

- 地址：`http://<电视IP>:8080`（电视 IP 显示在锁定界面）
- 接口：`/`（控制页）· `/lock`（锁定）· `/unlock`（解锁）· `/status`（状态 JSON）
- 手机与电视需在同一 Wi-Fi

## 使用说明

详见 [`说明书.md`](说明书.md)（含每个版本的变化历史）。

## 关键约束（TCL Android 6.0 实机总结）

- 无 root：`su` 不存在、`adb root` 被禁
- `set-device-owner` 因 backup 服务被砍而崩溃；`set-profile-owner` 可成功，但 ROM 的 `setLockTaskPackages` 只认 Device Owner → **Lock Task 硬锁不可行**，走「准锁机」方案
- 系统安装器被 ROM 接管、`pm install` 被禁 → 安装必须走当贝市场 APK 管理器
- TCL 对后台启动 Activity 有限制（约 3 秒延迟/拦截）→ Home 弹回由 GuardService 轮询兜底