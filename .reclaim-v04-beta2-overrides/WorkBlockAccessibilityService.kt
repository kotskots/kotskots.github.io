package com.kostas.reclaim

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.CountDownTimer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast

class WorkBlockAccessibilityService : AccessibilityService() {
    private var lastAppBlockedAt = 0L
    private var lastWebScanAt = 0L
    private var lastWebsiteBlockedAt = 0L
    private var foregroundPackage: String? = null
    private var foregroundStartedAt = 0L

    private var frictionOverlay: View? = null
    private var frictionOverlayPackage: String? = null
    private var frictionBypassPackage: String? = null
    private var frictionTimer: CountDownTimer? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val packageName = event.packageName?.toString() ?: return
        if (packageName == applicationContext.packageName) {
            frictionBypassPackage = null
            removeFrictionOverlay()
            return
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            accountUsage(packageName)
            updateFrictionEntryState(packageName)
            if (handleAppRules(packageName)) return
        }

        if (!WebsitePreferences.shouldBlockNow(this)) return
        if (!WebsiteBlocker.isBrowser(packageName)) return

        val now = System.currentTimeMillis()
        if (now - lastWebScanAt < 450) return
        lastWebScanAt = now

        val root = rootInActiveWindow ?: return
        val enabledCategories = WebsitePreferences.categories(this)
        val customDomains = WebsitePreferences.customDomains(this)
        val hit = findBlockedWebsite(root, enabledCategories, customDomains)
        if (hit != null && now - lastWebsiteBlockedAt >= 1200) {
            lastWebsiteBlockedAt = now
            val wentBack = performGlobalAction(GLOBAL_ACTION_BACK)
            if (!wentBack) performGlobalAction(GLOBAL_ACTION_HOME)
            ProductivityRepository(this).logEvent("blocked_site", hit)
            Toast.makeText(this, "Website blocked · $hit", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateFrictionEntryState(packageName: String) {
        val bypass = frictionBypassPackage
        if (bypass != null && packageName != bypass && !isSystemTransient(packageName)) {
            frictionBypassPackage = null
        }
        val overlayPkg = frictionOverlayPackage
        if (overlayPkg != null && packageName != overlayPkg && !isSystemTransient(packageName)) {
            removeFrictionOverlay()
        }
    }

    private fun isSystemTransient(packageName: String): Boolean =
        packageName == "android" || packageName.startsWith("com.android.systemui") || packageName.contains("permissioncontroller", true)

    private fun accountUsage(newPackage: String) {
        val now = System.currentTimeMillis()
        val previous = foregroundPackage
        if (previous != null && previous != newPackage && foregroundStartedAt > 0L) {
            val seconds = ((now - foregroundStartedAt) / 1000L).coerceIn(0L, 600L)
            ProductivityRepository(this).addUsageSeconds(previous, seconds)
        }
        if (previous != newPackage) {
            foregroundPackage = newPackage
            foregroundStartedAt = now
        }
    }

    private fun handleAppRules(packageName: String): Boolean {
        val repo = ProductivityRepository(this)
        val active = FocusPreferences.isActive(this)
        val exempt = isSystemTransient(packageName) || packageName.contains("launcher", ignoreCase = true)

        if (active && !exempt) {
            val blocked = packageName in FocusPreferences.blockedPackages(this)
            val whitelistBlocked = FocusPreferences.whitelistMode(this) && packageName !in FocusPreferences.allowedPackages(this)
            if (blocked || whitelistBlocked) {
                removeFrictionOverlay()
                blockApp(if (whitelistBlocked) "Not allowed in this focus profile" else "Blocked by Reclaim Work Mode")
                repo.logEvent("blocked_app", packageName)
                return true
            }
        }

        val budget = repo.appBudgets()[packageName] ?: 0
        if (budget > 0 && repo.usageSecondsForToday(packageName) >= budget * 60L) {
            removeFrictionOverlay()
            blockApp("Daily Reclaim allowance used")
            repo.logEvent("blocked_budget", packageName, metadata = "${budget}m")
            return true
        }

        if (!active && packageName in repo.frictionPackages() && packageName != frictionBypassPackage) {
            showFrictionOverlay(packageName, repo)
            return true
        }
        return false
    }

    private fun showFrictionOverlay(packageName: String, repo: ProductivityRepository) {
        if (frictionOverlayPackage == packageName && frictionOverlay != null) return
        removeFrictionOverlay()

        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
        val seconds = repo.frictionSeconds().coerceIn(0, 120)

        val panelBackground = GradientDrawable().apply {
            setColor(Color.rgb(24, 24, 27))
            cornerRadius = 36f
            setStroke(2, Color.rgb(70, 70, 78))
        }
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(42, 48, 42, 48)
            setBackgroundColor(Color.argb(245, 8, 8, 10))
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(42, 40, 42, 36)
            background = panelBackground
        }
        screen.addView(panel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        panel.addView(TextView(this).apply {
            text = "Reclaim pause"
            textSize = 14f
            setTextColor(Color.rgb(180, 180, 190))
        })
        panel.addView(TextView(this).apply {
            text = "Are you sure you want to open $label?"
            textSize = 25f
            setTextColor(Color.WHITE)
            setPadding(0, 14, 0, 8)
        })
        panel.addView(TextView(this).apply {
            text = "Wait a moment and choose why you're opening it. The app is still open underneath this screen."
            textSize = 15f
            setTextColor(Color.rgb(205, 205, 212))
            setPadding(0, 0, 0, 20)
        })

        val reasons = listOf("I actually need something", "Habit", "Bored", "Avoiding work")
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        reasons.forEachIndexed { index, reason ->
            group.addView(RadioButton(this).apply {
                id = 2000 + index
                text = reason
                textSize = 16f
                setTextColor(Color.WHITE)
                buttonTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                setPadding(0, 5, 0, 5)
            })
        }
        group.check(2001)
        panel.addView(group)

        val countdown = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 14)
        }
        panel.addView(countdown)

        val continueButton = Button(this).apply {
            text = "Continue to $label"
            isEnabled = seconds == 0
        }
        val backButton = Button(this).apply { text = "Back to work" }
        panel.addView(continueButton)
        panel.addView(backButton)

        fun selectedReason(): String = reasons.getOrElse(group.checkedRadioButtonId - 2000) { "Habit" }

        continueButton.setOnClickListener {
            val reason = selectedReason()
            repo.logEvent("distraction_open", label, metadata = reason)
            if (reason == "Avoiding work") repo.logEvent("urge", label, metadata = "avoiding_work")
            frictionBypassPackage = packageName
            removeFrictionOverlay(clearBypass = false)
        }
        backButton.setOnClickListener {
            repo.logEvent("urge_resisted", label, metadata = selectedReason())
            frictionBypassPackage = null
            removeFrictionOverlay()
            performGlobalAction(GLOBAL_ACTION_HOME)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(screen, params)
            frictionOverlay = screen
            frictionOverlayPackage = packageName
            repo.logEvent("friction_prompt", label)
        } catch (_: Throwable) {
            frictionOverlay = null
            frictionOverlayPackage = null
            Toast.makeText(this, "Reclaim couldn't show the pause screen", Toast.LENGTH_SHORT).show()
            return
        }

        if (seconds > 0) {
            frictionTimer = object : CountDownTimer(seconds * 1000L, 1000L) {
                override fun onTick(ms: Long) {
                    countdown.text = "Wait ${(ms + 999L) / 1000L}s before deciding"
                }
                override fun onFinish() {
                    countdown.text = "Choose deliberately"
                    continueButton.isEnabled = true
                }
            }.start()
        } else countdown.text = "Choose deliberately"
    }

    private fun removeFrictionOverlay(clearBypass: Boolean = false) {
        frictionTimer?.cancel()
        frictionTimer = null
        val view = frictionOverlay
        frictionOverlay = null
        frictionOverlayPackage = null
        if (clearBypass) frictionBypassPackage = null
        if (view != null) runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeViewImmediate(view) }
    }

    private fun blockApp(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastAppBlockedAt < 700) return
        lastAppBlockedAt = now
        performGlobalAction(GLOBAL_ACTION_HOME)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun findBlockedWebsite(root: AccessibilityNodeInfo, categories: Set<WebsiteCategory>, customDomains: Set<String>): String? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < 180) {
            val node = stack.removeLast()
            visited++
            val candidates = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
            candidates.forEach { candidate ->
                val match = WebsiteBlocker.blockedCategoryFor(candidate, categories, customDomains)
                if (match != null) return match
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(stack::add)
        }
        return null
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        removeFrictionOverlay(clearBypass = true)
        super.onDestroy()
    }
}
