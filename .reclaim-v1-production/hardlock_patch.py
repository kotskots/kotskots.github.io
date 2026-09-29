from pathlib import Path
import re, sys

root = Path(sys.argv[1]).resolve()

def read(rel):
    p=root/rel
    if not p.exists(): raise SystemExit(f'missing {rel}')
    return p.read_text()

def write(rel,s):
    p=root/rel; p.parent.mkdir(parents=True,exist_ok=True); p.write_text(s)

# version
g='app/build.gradle.kts'
s=read(g)
s,n=re.subn(r'versionCode\s*=\s*\d+', 'versionCode = 13', s, count=1)
if n!=1: raise SystemExit('versionCode replacement failed')
s,n=re.subn(r'versionName\s*=\s*"[^"]+"', 'versionName = "1.0.3"', s, count=1)
if n!=1: raise SystemExit('versionName replacement failed')
write(g,s)

# Device Admin receiver + description/xml.
manifest='app/src/main/AndroidManifest.xml'
s=read(manifest)
if 'ReclaimDeviceAdminReceiver' not in s:
    marker='        <receiver android:name=".ReminderReceiver" android:exported="false" />'
    receiver='''        <receiver\n            android:name=".ReclaimDeviceAdminReceiver"\n            android:description="@string/device_admin_description"\n            android:label="Reclaim Locked Mode protection"\n            android:permission="android.permission.BIND_DEVICE_ADMIN"\n            android:exported="true">\n            <meta-data\n                android:name="android.app.device_admin"\n                android:resource="@xml/reclaim_device_admin" />\n            <intent-filter>\n                <action android:name="android.app.action.DEVICE_ADMIN_ENABLED" />\n            </intent-filter>\n        </receiver>\n\n'''
    if marker not in s: raise SystemExit('manifest marker missing')
    s=s.replace(marker,receiver+marker,1)
write(manifest,s)

strings='app/src/main/res/values/strings.xml'
s=read(strings)
if 'device_admin_description' not in s:
    s=s.replace('</resources>','    <string name="device_admin_description">Adds uninstall resistance while a Reclaim Locked Mode session is active.</string>\n</resources>')
write(strings,s)
write('app/src/main/res/xml/reclaim_device_admin.xml','''<?xml version="1.0" encoding="utf-8"?>\n<device-admin xmlns:android="http://schemas.android.com/apk/res/android">\n    <uses-policies>\n        <force-lock />\n        <reset-password />\n    </uses-policies>\n</device-admin>\n''')
write('app/src/main/java/com/kostas/reclaim/LockdownPolicy.kt','''package com.kostas.reclaim\n\nimport android.app.admin.DeviceAdminReceiver\nimport android.app.admin.DevicePolicyManager\nimport android.content.ComponentName\nimport android.content.Context\nimport android.content.Intent\n\nclass ReclaimDeviceAdminReceiver : DeviceAdminReceiver()\n\nobject LockdownPolicy {\n    private const val PREFS = "reclaim_lockdown_policy"\n    private const val OWNER_BLOCK_APPLIED = "owner_uninstall_block_applied"\n\n    fun adminComponent(context: Context) = ComponentName(context, ReclaimDeviceAdminReceiver::class.java)\n\n    fun isAdminActive(context: Context): Boolean {\n        val dpm = context.getSystemService(DevicePolicyManager::class.java)\n        return dpm?.isAdminActive(adminComponent(context)) == true\n    }\n\n    fun isDeviceOwner(context: Context): Boolean {\n        val dpm = context.getSystemService(DevicePolicyManager::class.java)\n        return dpm?.isDeviceOwnerApp(context.packageName) == true\n    }\n\n    fun requestAdmin(context: Context) {\n        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {\n            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent(context))\n            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Reclaim uses device-admin protection to make Locked Mode harder to bypass or uninstall.")\n            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)\n        }\n        runCatching { context.startActivity(intent) }\n    }\n\n    fun engage(context: Context) {\n        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return\n        if (dpm.isDeviceOwnerApp(context.packageName)) {\n            runCatching {\n                dpm.setUninstallBlocked(adminComponent(context), context.packageName, true)\n                prefs(context).edit().putBoolean(OWNER_BLOCK_APPLIED, true).apply()\n            }\n        }\n    }\n\n    fun disengage(context: Context) {\n        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return\n        if (dpm.isDeviceOwnerApp(context.packageName) && prefs(context).getBoolean(OWNER_BLOCK_APPLIED, false)) {\n            runCatching { dpm.setUninstallBlocked(adminComponent(context), context.packageName, false) }\n            prefs(context).edit().putBoolean(OWNER_BLOCK_APPLIED, false).apply()\n        }\n    }\n\n    fun refresh(context: Context) {\n        if (FocusPreferences.isLockedActive(context)) engage(context) else disengage(context)\n    }\n\n    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)\n}\n''')

# Focus/website preferences cannot be weakened during a locked session.
f='app/src/main/java/com/kostas/reclaim/FocusPreferences.kt'
s=read(f)
s=s.replace('''    fun setBlockedPackages(context: Context, packages: Set<String>) {\n        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)''','''    fun setBlockedPackages(context: Context, packages: Set<String>) {\n        if (isLockedActive(context)) return\n        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)''',1)
s=s.replace('fun setWhitelistMode(context: Context, value: Boolean) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(WHITELIST_MODE, value).apply() }','fun setWhitelistMode(context: Context, value: Boolean) { if (isLockedActive(context)) return; context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(WHITELIST_MODE, value).apply() }',1)
s=s.replace('fun setAllowedPackages(context: Context, packages: Set<String>) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(ALLOWED_PACKAGES, packages).apply() }','fun setAllowedPackages(context: Context, packages: Set<String>) { if (isLockedActive(context)) return; context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(ALLOWED_PACKAGES, packages).apply() }',1)
needle='''            .putLong(STARTED_AT, now)\n            .apply()\n    }\n\n    fun isLockedActive'''
if needle not in s: raise SystemExit('Focus start engage marker missing')
s=s.replace(needle,'''            .putLong(STARTED_AT, now)\n            .apply()\n        if (strictMode.equals("LOCKED", ignoreCase = true)) LockdownPolicy.engage(context)\n    }\n\n    fun isLockedActive''',1)
needle='''            .putString(SESSION_ID, "")\n            .apply()\n        return true'''
if needle not in s: raise SystemExit('Focus stop disengage marker missing')
s=s.replace(needle,'''            .putString(SESSION_ID, "")\n            .apply()\n        LockdownPolicy.disengage(context)\n        return true''',1)
needle='''            .putLong(STARTED_AT, now)\n            .apply()\n    }\n\n    fun until'''
if needle not in s: raise SystemExit('Focus startUntil engage marker missing')
s=s.replace(needle,'''            .putLong(STARTED_AT, now)\n            .apply()\n        if (strictMode.equals("LOCKED", ignoreCase = true)) LockdownPolicy.engage(context)\n    }\n\n    fun until''',1)
s=s.replace('''    fun setEnabled(context: Context, enabled: Boolean) {\n        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)''','''    fun setEnabled(context: Context, enabled: Boolean) {\n        if (FocusPreferences.isLockedActive(context)) return\n        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)''',1)
s=s.replace('''    fun setWorkOnly(context: Context, value: Boolean) {\n        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)''','''    fun setWorkOnly(context: Context, value: Boolean) {\n        if (FocusPreferences.isLockedActive(context)) return\n        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)''',1)
s=s.replace('''    fun setCategories(context: Context, values: Set<WebsiteCategory>) {\n        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)''','''    fun setCategories(context: Context, values: Set<WebsiteCategory>) {\n        if (FocusPreferences.isLockedActive(context)) return\n        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)''',1)
s=s.replace('''    fun setCustomDomains(context: Context, domains: Set<String>) {\n        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)''','''    fun setCustomDomains(context: Context, domains: Set<String>) {\n        if (FocusPreferences.isLockedActive(context)) return\n        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)''',1)
write(f,s)

# Emergency-unlock settings cannot be weakened once locked.
f='app/src/main/java/com/kostas/reclaim/ProductionPreferences.kt'; s=read(f)
s=s.replace('fun setEmergencyUnlockEnabled(context: Context, value: Boolean) = p(context).edit().putBoolean("emergency_unlock", value).apply()','fun setEmergencyUnlockEnabled(context: Context, value: Boolean) { if (!FocusPreferences.isLockedActive(context)) p(context).edit().putBoolean("emergency_unlock", value).apply() }',1)
s=s.replace('fun setEmergencyUnlockDelaySeconds(context: Context, value: Int) = p(context).edit().putInt("emergency_unlock_delay", value.coerceIn(60, 1800)).apply()','fun setEmergencyUnlockDelaySeconds(context: Context, value: Int) { if (!FocusPreferences.isLockedActive(context)) p(context).edit().putInt("emergency_unlock_delay", value.coerceIn(60, 1800)).apply() }',1)
write(f,s)

# Re-engage after reboot.
f='app/src/main/java/com/kostas/reclaim/BootReceiver.kt'; s=read(f)
needle='        if (WindowsSyncPreferences.isConfigured(context)) WindowsSyncService.start(context)'
if needle not in s: raise SystemExit('Boot marker missing')
s=s.replace(needle,'        LockdownPolicy.refresh(context)\n        if (FocusPreferences.isActive(context) && WebsitePreferences.enabled(context) && android.net.VpnService.prepare(context) == null) DnsVpnService.start(context)\n        if (WindowsSyncPreferences.isConfigured(context)) WindowsSyncService.start(context)',1)
write(f,s)

# Hide/minimize mandatory foreground service notifications using new channels.
for rel, oldid, newid, oldname, newname in [
    ('app/src/main/java/com/kostas/reclaim/DnsVpnService.kt','reclaim_web_filter','reclaim_web_filter_hidden_v3','Website blocker','Website blocker (background)'),
    ('app/src/main/java/com/kostas/reclaim/WindowsSyncService.kt','reclaim_windows_sync','reclaim_sync_background_hidden_v3','Windows LAN sync','Windows LAN sync (background)')]:
    s=read(rel)
    s=s.replace(f'private const val CHANNEL_ID = "{oldid}"',f'private const val CHANNEL_ID = "{newid}"',1)
    if rel.endswith('DnsVpnService.kt'):
        s=s.replace('.setOngoing(true)\n            .setContentIntent(openIntent)', '.setOngoing(true)\n            .setSilent(true)\n            .setPriority(NotificationCompat.PRIORITY_MIN)\n            .setShowWhen(false)\n            .setContentIntent(openIntent)',1)
        s=s.replace(f'NotificationChannel(CHANNEL_ID, "{oldname}", NotificationManager.IMPORTANCE_LOW)',f'NotificationChannel(CHANNEL_ID, "{newname}", NotificationManager.IMPORTANCE_NONE)',1)
    else:
        s=s.replace('.setContentText(text).setOngoing(true)\n        .setContentIntent', '.setContentText(text).setOngoing(true).setSilent(true).setPriority(NotificationCompat.PRIORITY_MIN).setShowWhen(false)\n        .setContentIntent',1)
        s=s.replace(f'NotificationChannel(CHANNEL_ID, "{oldname}", NotificationManager.IMPORTANCE_LOW)',f'NotificationChannel(CHANNEL_ID, "{newname}", NotificationManager.IMPORTANCE_NONE)',1)
    write(rel,s)

# Block common escape routes in Locked Mode.
f='app/src/main/java/com/kostas/reclaim/WorkBlockAccessibilityService.kt'; s=read(f)
needle='''        val active = FocusPreferences.isActive(this)\n        val exempt = isSystemTransient(packageName) || packageName.contains("launcher", ignoreCase = true)\n'''
if needle not in s: raise SystemExit('WorkBlock marker missing')
s=s.replace(needle,'''        val active = FocusPreferences.isActive(this)\n        val locked = FocusPreferences.isLockedActive(this)\n        val exempt = isSystemTransient(packageName) || packageName.contains("launcher", ignoreCase = true)\n\n        if (locked && isLockedModeEscapeRoute(packageName)) {\n            removeFrictionOverlay()\n            blockApp(packageName, "Locked Mode is active. Finish the session or use Reclaim emergency unlock.")\n            repo.logEvent("blocked_escape_route", packageName)\n            return true\n        }\n''',1)
helper='''\n    private fun isLockedModeEscapeRoute(packageName: String): Boolean {\n        val exact = setOf(\n            "com.android.settings",\n            "com.google.android.packageinstaller",\n            "com.android.packageinstaller",\n            "com.google.android.permissioncontroller",\n            "com.android.permissioncontroller",\n            "com.android.vending"\n        )\n        return packageName in exact ||\n            packageName.contains("packageinstaller", ignoreCase = true) ||\n            packageName.contains("permissioncontroller", ignoreCase = true)\n    }\n\n'''
marker='    private fun isWithinAllowedWindow(limit: AppLimit): Boolean {'
if marker not in s: raise SystemExit('WorkBlock helper marker missing')
s=s.replace(marker,helper+marker,1)
write(f,s)

# Focus UI: require protection before LOCKED starts; deliberate emergency unlock; freeze sync settings.
f='app/src/main/java/com/kostas/reclaim/ProductionScreens.kt'; s=read(f)
old='''                    Button(enabled=!active,onClick={ FocusPreferences.setBlockedPackages(context,selectedApps); FocusPreferences.setWhitelistMode(context,false); FocusPreferences.start(context,quickDuration,quickIntention,"Quick",quickStrict.name); prod.logEvent("session_start","Quick",metadata=quickIntention); WindowsSyncService.start(context); activeUntil=FocusPreferences.until(context) },modifier=Modifier.fillMaxWidth()){Text(if(quickStrict==StrictMode.LOCKED) "Start LOCKED session" else "Start session")}'''
new='''                    Button(enabled=!active,onClick={\n                        if (quickStrict==StrictMode.LOCKED && !LockdownPolicy.isAdminActive(context)) {\n                            LockdownPolicy.requestAdmin(context)\n                        } else {\n                            FocusPreferences.setBlockedPackages(context,selectedApps); FocusPreferences.setWhitelistMode(context,false); FocusPreferences.start(context,quickDuration,quickIntention,"Quick",quickStrict.name); prod.logEvent("session_start","Quick",metadata=quickIntention); WindowsSyncService.start(context); activeUntil=FocusPreferences.until(context)\n                        }\n                    },modifier=Modifier.fillMaxWidth()){Text(if(quickStrict==StrictMode.LOCKED && !LockdownPolicy.isAdminActive(context)) "Enable Locked Mode protection first" else if(quickStrict==StrictMode.LOCKED) "Start LOCKED session" else "Start session")}'''
if old not in s: raise SystemExit('quick locked button marker missing')
s=s.replace(old,new,1)
pattern=r'@Composable\nprivate fun ProductionEmergencyUnlockDialog\(context: Context, prod: ProductivityRepository, onDismiss: \(\) -> Unit, onUnlock: \(\) -> Unit\) \{.*?\n\}\n\n@Composable\nfun ProductionSmokingScreen'
repl='''@Composable\nprivate fun ProductionEmergencyUnlockDialog(context: Context, prod: ProductivityRepository, onDismiss: () -> Unit, onUnlock: () -> Unit) {\n    val total=ProductionSettings.emergencyUnlockDelaySeconds(context).coerceAtLeast(60)\n    val minimumChars = 1200\n    var remaining by remember { mutableIntStateOf(total) }\n    var reason by remember { mutableStateOf("") }\n    LaunchedEffect(Unit){while(remaining>0){delay(1000);remaining--}}\n    AlertDialog(onDismissRequest=onDismiss,title={Text("Break Locked Mode?")},text={LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.heightIn(max=560.dp)){\n        item{Text("Locked Mode exists because you chose this session before the urge to quit appeared. If this is not a real emergency, close this screen and keep the promise you made to yourself.")}\n        item{Text(if(remaining>0)"You still have ${remaining}s to reconsider." else "If you still want to leave, type at least $minimumChars characters explaining what you planned to do, why you want to stop now, what happens if you give in, and why ending the session is worth it.",color=MaterialTheme.colorScheme.onSurfaceVariant)}\n        item{OutlinedTextField(reason,{v->val delta=v.length-reason.length;if(v.length<=4000&&delta<=16)reason=v},label={Text("Type it yourself · ${reason.length}/$minimumChars")},supportingText={Text("Large pasted blocks are ignored.")},minLines=10,maxLines=16)}\n        item{Text("This unlock is recorded in your Reclaim history.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}\n    }},confirmButton={Button(enabled=remaining==0&&reason.trim().length>=minimumChars,onClick={prod.logEvent("emergency_unlock_reason",reason.trim());onUnlock()}){Text("End Locked Mode")}},dismissButton={TextButton(onClick=onDismiss){Text("Stay locked")}})\n}\n\n@Composable\nfun ProductionSmokingScreen'''
s2,n=re.subn(pattern,repl,s,count=1,flags=re.S)
if n!=1: raise SystemExit(f'emergency dialog replacement failed {n}')
s=s2
if 'FocusPreferences.stop(context,force=true)' not in s:
    needle='if(showEmergency) ProductionEmergencyUnlockDialog(context,prod,onDismiss={showEmergency=false}){FocusPreferences.stop(context);'
    if needle not in s: raise SystemExit('emergency callback marker missing')
    s=s.replace(needle,'if(showEmergency) ProductionEmergencyUnlockDialog(context,prod,onDismiss={showEmergency=false}){FocusPreferences.stop(context,force=true);',1)
s=s.replace('Switch(d.enabled,onCheckedChange=', 'Switch(d.enabled,enabled=!locked,onCheckedChange=',1)
s=s.replace('Row{TextButton(onClick={renameDevice=d}){Text("Rename")};TextButton(onClick={WindowsSyncPreferences.remove(context,d.id);devices=WindowsSyncPreferences.devices(context)}){Text("Forget")}}', 'Row{TextButton(enabled=!locked,onClick={renameDevice=d}){Text("Rename")};TextButton(enabled=!locked,onClick={WindowsSyncPreferences.remove(context,d.id);devices=WindowsSyncPreferences.devices(context)}){Text("Forget")}}',1)
s=s.replace('Button(enabled=!discovering&&devices.size<3,onClick=', 'Button(enabled=!locked&&!discovering&&devices.size<3,onClick=',1)
s=s.replace('Button(enabled=devices.size<3&&pairHost.isNotBlank()&&pairCode.length>=4,onClick=', 'Button(enabled=!locked&&devices.size<3&&pairHost.isNotBlank()&&pairCode.length>=4,onClick=',1)
settings_marker='''        item{Text("Settings",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold);Text("Local-first controls. Sync uses your LAN only.",color=MaterialTheme.colorScheme.onSurfaceVariant)}'''
if settings_marker in s and 'Locked Mode protection' not in s:
    card='''\n        item{Card{Column(Modifier.padding(13.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){\n            Text("Locked Mode protection",fontWeight=FontWeight.Bold)\n            val adminActive=LockdownPolicy.isAdminActive(context); val owner=LockdownPolicy.isDeviceOwner(context)\n            Text(when{owner->"Device Owner protection active. Uninstall blocking is enforced during Locked Mode.";adminActive->"Device Admin protection active. Settings and uninstall routes are blocked while Locked Mode runs.";else->"Enable protection before starting a Locked session."},style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)\n            if(!adminActive) Button(onClick={LockdownPolicy.requestAdmin(context)},modifier=Modifier.fillMaxWidth()){Text("Enable Locked Mode protection")}\n        }}}'''
    s=s.replace(settings_marker,settings_marker+card,1)
old='private fun productionStartProfile(context:Context,prod:ProductivityRepository,p:FocusProfile){FocusPreferences.setBlockedPackages(context,p.blockedPackages);'
new='private fun productionStartProfile(context:Context,prod:ProductivityRepository,p:FocusProfile){if(p.strictMode==StrictMode.LOCKED&&!LockdownPolicy.isAdminActive(context)){LockdownPolicy.requestAdmin(context);return};FocusPreferences.setBlockedPackages(context,p.blockedPackages);'
if old not in s: raise SystemExit('productionStartProfile marker missing')
s=s.replace(old,new,1)
write(f,s)

checks={
'app/build.gradle.kts':['versionCode = 13','versionName = "1.0.3"'],
'app/src/main/AndroidManifest.xml':['ReclaimDeviceAdminReceiver','@xml/reclaim_device_admin'],
'app/src/main/java/com/kostas/reclaim/ProductionScreens.kt':['minimumChars = 1200','FocusPreferences.stop(context,force=true)','Enable Locked Mode protection first'],
'app/src/main/java/com/kostas/reclaim/WorkBlockAccessibilityService.kt':['isLockedModeEscapeRoute','blocked_escape_route'],
'app/src/main/java/com/kostas/reclaim/DnsVpnService.kt':['IMPORTANCE_NONE','reclaim_web_filter_hidden_v3'],
'app/src/main/java/com/kostas/reclaim/WindowsSyncService.kt':['IMPORTANCE_NONE','reclaim_sync_background_hidden_v3'],
}
for rel, needles in checks.items():
    text=read(rel)
    for needle in needles:
        if needle not in text: raise SystemExit(f'sanity missing {needle} in {rel}')
print('hardlock patch OK')
