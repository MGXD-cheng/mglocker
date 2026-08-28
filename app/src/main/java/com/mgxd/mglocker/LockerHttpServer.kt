package com.mgxd.mglocker

import android.content.Context
import android.content.Intent
import fi.iki.elonen.NanoHTTPD
import java.net.NetworkInterface

/**
 * 局域网远程控制 HTTP 服务器（NanoHTTPD，端口 8080）。
 *
 * 同一局域网内的电脑/手机浏览器访问：
 *   GET /         → 控制页面（锁定 / 解锁按钮）
 *   GET /lock     → 拉起 MainActivity（重新锁定）
 *   GET /unlock   → 关闭 MainActivity（解除锁定，不自动弹回）
 *   GET /status   → JSON 状态
 *
 * 注意：本服务不设鉴权，仅限可信局域网内使用。
 */
class LockerHttpServer(
    private val context: Context,
    port: Int = 8080
) : NanoHTTPD(port) {

    override fun serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val uri = session.uri ?: "/"
        return when (uri) {
            "/lock" -> {
                lock()
                jsonResult("lock")
            }
            "/unlock" -> {
                unlock()
                jsonResult("unlock")
            }
            "/status" -> newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "application/json; charset=utf-8",
                statusJson()
            )
            "/settings" -> handleSettings(session)
            "/favicon.ico" -> newFixedLengthResponse(
                NanoHTTPD.Response.Status.NO_CONTENT,
                "text/plain",
                ""
            )
            else -> newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK,
                "text/html; charset=utf-8",
                page()
            )
        }
    }

    /** 拉起锁定界面 */
    private fun lock() {
        // 先复位退出标志：无论 Activity 走 onCreate 还是 onNewIntent，都确保是真锁定
        MainActivity.allowExit = false
        try {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                ).putExtra("remote_lock", true)
            )
        } catch (_: Exception) {
        }
    }

    /** 解除锁定：标记允许退出并通知所有 MainActivity 实例退出 */
    private fun unlock() {
        MainActivity.allowExit = true
        context.sendBroadcast(Intent(MainActivity.ACTION_UNLOCK))
    }

    /** 设置 API：GET 读取（lock_enabled / scheduled_lock / scheduled_range_*），POST 保存（表单） */
    private fun handleSettings(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (session.method == NanoHTTPD.Method.POST) {
            val files = HashMap<String, String>()
            try {
                session.parseBody(files)
            } catch (_: Exception) {
            }
            val timePattern = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
            val lockEnabled = session.parms.get("lock_enabled") == "1"
            val scheduled = (session.parms.get("scheduled_lock") ?: "").trim()
            if (scheduled.isNotEmpty() && !timePattern.matches(scheduled)) {
                return settingsResult(false, "定时时间格式应为 HH:mm，例如 21:30")
            }
            // 时间段锁定：独立开关 + 起止时间（支持跨午夜，如 22:00-06:00）
            val rangeEnabled = session.parms.get("scheduled_range_enabled") == "1"
            val rangeStart = (session.parms.get("scheduled_range_start") ?: "").trim()
            val rangeEnd = (session.parms.get("scheduled_range_end") ?: "").trim()
            if (rangeEnabled && (!timePattern.matches(rangeStart) || !timePattern.matches(rangeEnd))) {
                return settingsResult(false, "时间段起止时间格式应为 HH:mm，例如 22:00-06:00")
            }
            SettingsStore.setLockEnabled(context, lockEnabled)
            SettingsStore.setScheduledLockTime(context, scheduled)
            SettingsStore.setScheduledRangeEnabled(context, rangeEnabled)
            SettingsStore.setScheduledRangeStart(context, if (rangeEnabled) rangeStart else "")
            SettingsStore.setScheduledRangeEnd(context, if (rangeEnabled) rangeEnd else "")
            return settingsResult(true, "已保存，重启后依然生效")
        }
        return newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            "application/json; charset=utf-8",
            "{\"lock_enabled\":${SettingsStore.isLockEnabled(context)}," +
                "\"scheduled_lock\":\"${SettingsStore.scheduledLockTime(context)}\"," +
                "\"scheduled_range_enabled\":${SettingsStore.isScheduledRangeEnabled(context)}," +
                "\"scheduled_range_start\":\"${SettingsStore.scheduledRangeStart(context)}\"," +
                "\"scheduled_range_end\":\"${SettingsStore.scheduledRangeEnd(context)}\"}"
        )
    }

    /** 设置保存结果 JSON */
    private fun settingsResult(ok: Boolean, msg: String): NanoHTTPD.Response {
        return newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            "application/json; charset=utf-8",
            "{\"ok\":$ok,\"msg\":\"$msg\"}"
        )
    }

    private fun statusJson(): String {
        val state = if (MainActivity.allowExit) "unlocked" else "locked"
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
        return "{\"app\":\"MG Locker\",\"version\":\"$version\",\"state\":\"$state\",\"ip\":\"${localIp()}\",\"port\":8080}"
    }

    private fun page(): String {
        val ip = localIp()
        return """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>MG Locker 控制台</title>
            <style>
              body { font-family: "PingFang SC","Microsoft YaHei",sans-serif; background:#0f172a; color:#e2e8f0;
                     display:flex; align-items:center; justify-content:center; min-height:100vh; margin:0; }
              .card { background:#1e293b; border-radius:20px; padding:40px 48px; text-align:center;
                      box-shadow:0 20px 60px rgba(0,0,0,.5); max-width:420px; width:90%; }
              h1 { font-size:26px; margin:0 0 8px; }
              .ip { color:#94a3b8; font-size:14px; margin-bottom:28px; }
              .btn { display:block; width:100%; padding:16px; margin:10px 0; border:none; border-radius:12px;
                     font-size:18px; font-weight:600; cursor:pointer; color:#fff; }
              .lock { background:#16a34a; }
              .unlock { background:#dc2626; }
              .note { margin-top:22px; color:#64748b; font-size:12px; line-height:1.6; }
              .mask { display:none; position:fixed; inset:0; background:rgba(0,0,0,.65); align-items:center;
                      justify-content:center; z-index:99; }
              .mask.show { display:flex; }
              .dlg { background:#1e293b; border-radius:16px; padding:32px; text-align:center; max-width:340px; width:86%; }
              .dlg p { font-size:18px; margin:0 0 24px; line-height:1.5; }
              .dlg .row { display:flex; gap:12px; }
              .dlg .ok { flex:1; background:#dc2626; padding:14px; border:none; border-radius:10px; color:#fff;
                         font-size:16px; font-weight:600; cursor:pointer; }
              .dlg .no { flex:1; background:#334155; padding:14px; border:none; border-radius:10px; color:#cbd5e1;
                         font-size:16px; cursor:pointer; }
              .badge { display:inline-block; padding:6px 18px; border-radius:999px; font-size:14px; font-weight:600;
                       margin-bottom:18px; }
              .badge.locked { background:rgba(220,38,38,.15); color:#f87171; border:1px solid rgba(220,38,38,.4); }
              .badge.unlocked { background:rgba(34,197,94,.15); color:#4ade80; border:1px solid rgba(34,197,94,.4); }
              .toast { position:fixed; left:50%; bottom:40px; transform:translateX(-50%); background:#0b1220;
                       color:#e2e8f0; padding:12px 24px; border-radius:10px; font-size:15px; opacity:0;
                       transition:opacity .25s; pointer-events:none; z-index:999;
                       box-shadow:0 8px 30px rgba(0,0,0,.5); border:1px solid #334155; }
              .toast.show { opacity:1; }
              .settings { margin-top:26px; padding-top:22px; border-top:1px solid #334155; text-align:left; }
              .settings h2 { font-size:17px; margin:0 0 14px; text-align:center; color:#cbd5e1; }
              .set-row { display:flex; align-items:center; justify-content:space-between; padding:10px 0; font-size:15px; }
              .set-row small { color:#64748b; font-size:12px; margin-left:6px; }
              .tgl { position:relative; width:46px; height:26px; flex-shrink:0; }
              .tgl input { opacity:0; width:0; height:0; }
              .tgl .slider { position:absolute; inset:0; background:#334155; border-radius:999px; transition:.2s; cursor:pointer; }
              .tgl .slider:before { content:''; position:absolute; height:20px; width:20px; left:3px; top:3px; background:#e2e8f0; border-radius:50%; transition:.2s; }
              .tgl input:checked + .slider { background:#16a34a; }
              .tgl input:checked + .slider:before { transform:translateX(20px); }
              .set-time { background:#0f172a; color:#e2e8f0; border:1px solid #334155; border-radius:8px; padding:8px 10px; font-size:15px; width:130px; }
              .btn.save { background:#6366f1; margin-top:14px; }
            </style>
            </head>
            <body>
              <div class="card">
                <h1>🔒 MG Locker 控制台</h1>
                <div class="ip">设备：$ip:8080</div>
                <div class="badge locked" id="st">读取状态中…</div>
                <button class="btn lock" onclick="doAction('/lock')">🔒 立即锁定</button>
                <button class="btn unlock" onclick="confirmUnlock()">🔓 解除锁定</button>
                <div class="note">锁定：拉起锁屏界面并自动弹回<br>解锁：关闭锁屏界面（仅限远程管理）</div>
                <div class="settings">
                  <h2>⚙️ 自动锁定设置</h2>
                  <div class="set-row">
                    <span>锁定总开关</span>
                    <label class="tgl"><input type="checkbox" id="lockEnabled"><span class="slider"></span></label>
                  </div>
                  <div class="set-row">
                    <span>定时锁定<small>每天到点</small></span>
                    <input type="time" id="scheduledLock" class="set-time" value="">
                  </div>
                  <div class="set-row">
                    <span>时间段锁定<small>独立开关</small></span>
                    <label class="tgl"><input type="checkbox" id="rangeEnabled"><span class="slider"></span></label>
                  </div>
                  <div class="set-row">
                    <span>起 ~ 止<small>可跨午夜</small></span>
                    <span style="display:flex;gap:6px;align-items:center">
                      <input type="time" id="rangeStart" class="set-time" style="width:105px" value="">
                      <span style="color:#64748b">~</span>
                      <input type="time" id="rangeEnd" class="set-time" style="width:105px" value="">
                    </span>
                  </div>
                  <div style="color:#64748b;font-size:12px;line-height:1.7;padding:4px 0 2px;">
                    定时锁定：每天到点自动锁定一次，留空则仅开机锁定。<br>
                    时间段锁定：独立开关，开启后在设定时间段内自动锁定，<br>
                    &nbsp;&nbsp;视为手动锁定（不受总开关影响，解锁后到点仍会锁回）；<br>
                    &nbsp;&nbsp;支持跨午夜（如 22:00-06:00），起止相同=全天。<br>
                    关闭总开关后：开机不自动锁定、单点定时锁定暂停。<br>
                    已锁定时请用上方「解除锁定」。
                  </div>
                  <button class="btn save" onclick="saveSettings()">💾 保存设置（重启后依然生效）</button>
                </div>
              </div>
              <div class="mask" id="mask">
                <div class="dlg">
                  <p>确定要解除锁定吗？<br><small style="color:#94a3b8;font-size:13px">解锁后电视将退出锁屏界面</small></p>
                  <div class="row">
                    <button class="ok" onclick="doAction('/unlock')">确认解锁</button>
                    <button class="no" onclick="closeDlg()">取消</button>
                  </div>
                </div>
              </div>
              <div class="toast" id="toast"></div>
              <script>
                function showToast(msg){
                  var t=document.getElementById('toast');
                  t.textContent=msg; t.className='toast show';
                  setTimeout(function(){ t.className='toast'; },2200);
                }
                function loadStatus(){
                  fetch('/status',{cache:'no-store'}).then(function(r){return r.json()}).then(function(d){
                    var el=document.getElementById('st');
                    el.textContent = d.state==='locked' ? '🔒 已锁定' : '🔓 已解锁';
                    el.className = 'badge ' + d.state;
                  }).catch(function(){ document.getElementById('st').textContent='⚠️ 连接异常'; });
                }
                function confirmUnlock(){ document.getElementById('mask').classList.add('show'); }
                function closeDlg(){ document.getElementById('mask').classList.remove('show'); }
                function doAction(u){
                  fetch(u,{cache:'no-store'}).then(function(r){return r.json()}).then(function(d){
                    showToast(d.action==='lock' ? '✅ 已锁定' : '✅ 已解锁');
                    closeDlg(); loadStatus();
                  }).catch(function(){ showToast('❌ 操作失败，请确认网络'); closeDlg(); });
                }
                function loadSettings(){
                  fetch('/settings',{cache:'no-store'}).then(function(r){return r.json()}).then(function(d){
                    document.getElementById('lockEnabled').checked = !!d.lock_enabled;
                    document.getElementById('scheduledLock').value = d.scheduled_lock || '';
                    document.getElementById('rangeEnabled').checked = !!d.scheduled_range_enabled;
                    document.getElementById('rangeStart').value = d.scheduled_range_start || '';
                    document.getElementById('rangeEnd').value = d.scheduled_range_end || '';
                  }).catch(function(){});
                }
                function saveSettings(){
                  var body = 'lock_enabled=' + (document.getElementById('lockEnabled').checked ? 1 : 0) +
                             '&scheduled_lock=' + encodeURIComponent(document.getElementById('scheduledLock').value) +
                             '&scheduled_range_enabled=' + (document.getElementById('rangeEnabled').checked ? 1 : 0) +
                             '&scheduled_range_start=' + encodeURIComponent(document.getElementById('rangeStart').value) +
                             '&scheduled_range_end=' + encodeURIComponent(document.getElementById('rangeEnd').value);
                  fetch('/settings',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:body})
                    .then(function(r){return r.json()}).then(function(d){
                      showToast(d.ok ? '✅ 已保存，重启后依然生效' : '❌ ' + d.msg);
                      loadSettings(); loadStatus();
                    }).catch(function(){ showToast('❌ 保存失败，请确认网络'); });
                }
                loadStatus();
                loadSettings();
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    /** 操作结果 JSON（200，前端 fetch 可直接解析） */
    private fun jsonResult(action: String): NanoHTTPD.Response {
        val state = if (MainActivity.allowExit) "unlocked" else "locked"
        return newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            "application/json; charset=utf-8",
            "{\"ok\":true,\"action\":\"$action\",\"state\":\"$state\"}"
        )
    }

    /** 获取 Wi-Fi 局域网 IPv4 地址（用于页面展示） */
    private fun localIp(): String {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is java.net.Inet4Address) return addr.hostAddress ?: "unknown"
                }
            }
            "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }
}