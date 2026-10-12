package app.truenascompanion.quick

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import app.truenascompanion.TrueNasApp
import app.truenascompanion.data.model.Health
import app.truenascompanion.notify.DeepLink
import app.truenascompanion.notify.DeepLinkGuard
import app.truenascompanion.widget.WidgetRefreshWorker
import app.truenascompanion.widget.WidgetSnapshot
import app.truenascompanion.widget.WidgetStore
import kotlinx.coroutines.runBlocking

/**
 * 1.11.0: automation (Tasker and other automation apps). Off by default (System › App lock & privacy › Automation).
 *
 * Two doors, both following the 1.7.1 rule that nothing from outside may change the NAS without the in-app
 * confirmation:
 *
 * 1. **Intents for apps holding `<package>.permission.AUTOMATION`** (a "dangerous" permission: Android asks the user
 *    before granting it to another app). [AutomationReceiver] takes `ACTION_REFRESH` and `ACTION_QUERY_STATUS` (answers
 *    with an `ACTION_STATUS` broadcast only permission holders receive); [AutomationActivity] takes `ACTION_OPEN`
 *    (a screen) and `ACTION_QUICK_ACTION` (opens the quick action's confirmation, e.g. "Scrub tank?").
 * 2. **Tasker / Locale plugin** (Tasker can't hold custom permissions): the action or condition is configured inside
 *    YTN ([TaskerEditActivity]), which signs the plugin bundle with the app's private key ([DeepLinkGuard] secret).
 *    [TaskerFireReceiver] ignores bundles it didn't sign. Opening a screen or a quick action from a plugin posts a
 *    "Tap to continue" notification (Android doesn't let a broadcast open an activity in the background).
 *
 * Neither door can switch the server, skip a confirmation or the app lock, or reach anything but the screens below.
 */
object Automation {
    fun permission(context: Context) = "${context.packageName}.permission.AUTOMATION"
    fun action(context: Context, name: String) = "${context.packageName}.action.$name"

    const val REFRESH = "REFRESH"
    const val QUERY_STATUS = "QUERY_STATUS"
    const val STATUS = "STATUS"
    const val OPEN = "OPEN"
    const val QUICK_ACTION = "QUICK_ACTION"

    const val EXTRA_SCREEN = "screen"
    const val EXTRA_QUICK_ACTION = "quick_action"
    const val EXTRA_TARGET = "target"

    // Status extras
    const val X_SERVER = "server"
    const val X_HEALTH = "health"
    const val X_ALERTS = "alerts"
    const val X_ONLINE = "online"
    const val X_ERROR = "error"
    const val X_UPDATED = "updated_at"

    /** Screens automation may open (public name -> in-app destination). */
    val SCREENS: Map<String, String> = linkedMapOf(
        "dashboard" to DeepLink.DEST_DASHBOARD,
        "alerts" to DeepLink.DEST_ALERTS,
        "storage" to DeepLink.DEST_STORAGE,
        "apps" to DeepLink.DEST_APPS,
        "tasks" to DeepLink.DEST_TASKS,
        "checkup" to DeepLink.DEST_CHECKUP,
        "backup" to DeepLink.DEST_BACKUP,
    )
    val SCREEN_LABELS = mapOf(
        "dashboard" to "Home", "alerts" to "Alerts", "storage" to "Storage", "apps" to "Apps",
        "tasks" to "Running jobs", "checkup" to "Protection checkup", "backup" to "Backup & restore",
    )

    /** Quick actions automation may start (each opens its confirmation). Shell isn't offered. */
    val QUICK: List<QuickAction> = listOf(QuickAction.SCRUB_POOL, QuickAction.RESTART_APP, QuickAction.ALERTS)

    /** What a request turns into, decided purely (unit tested). */
    sealed interface Request {
        data object Refresh : Request
        data class Open(val destination: String) : Request
        data class Quick(val action: QuickAction, val target: String?) : Request
    }

    fun parse(kind: String?, screen: String?, quick: String?, target: String?): Request? = when (kind) {
        REFRESH -> Request.Refresh
        OPEN -> SCREENS[screen?.lowercase()]?.let { Request.Open(it) }
        QUICK_ACTION -> QuickAction.byId(quick)?.takeIf { it in QUICK }?.let { a ->
            Request.Quick(a, target?.trim()?.takeIf { it.isNotEmpty() && it.length <= 128 && a != QuickAction.ALERTS })
        }
        else -> null
    }

    /** The in-app intent (signed, active server) that opens the screen or the action's confirmation. */
    fun appIntent(context: Context, r: Request): Intent? = when (r) {
        Request.Refresh -> null
        is Request.Open -> QuickActions.intent(context, r.destination)
        is Request.Quick -> QuickActions.intent(context, r.action.destination, r.target)
    }

    fun refresh(context: Context) {
        WidgetRefreshWorker.refreshNow(context, force = true)
    }

    fun enabled(context: Context): Boolean =
        runCatching { runBlocking { (context.applicationContext as TrueNasApp).container.settings.automationEnabledOnce() } }.getOrDefault(false)

    fun healthName(h: Health): String = when (h) {
        Health.HEALTHY, Health.INFO -> "healthy"
        Health.WARNING -> "warning"
        Health.CRITICAL -> "critical"
        Health.UNKNOWN -> "unknown"
    }

    fun statusExtras(s: WidgetSnapshot): Bundle = Bundle().apply {
        putString(X_SERVER, s.serverName)
        putString(X_HEALTH, if (s.error != null) "unknown" else healthName(s.poolHealth))
        putInt(X_ALERTS, s.alertCount)
        putBoolean(X_ONLINE, s.error == null && s.serverName != null)
        s.error?.let { putString(X_ERROR, it) }
        putLong(X_UPDATED, s.updatedAt)
    }

    // --- Tasker / Locale plugin ---

    const val LOCALE_EDIT_SETTING = "com.twofortyfouram.locale.intent.action.EDIT_SETTING"
    const val LOCALE_FIRE_SETTING = "com.twofortyfouram.locale.intent.action.FIRE_SETTING"
    const val LOCALE_EDIT_CONDITION = "com.twofortyfouram.locale.intent.action.EDIT_CONDITION"
    const val LOCALE_QUERY_CONDITION = "com.twofortyfouram.locale.intent.action.QUERY_CONDITION"
    const val LOCALE_EXTRA_BUNDLE = "com.twofortyfouram.locale.intent.extra.BUNDLE"
    const val LOCALE_EXTRA_BLURB = "com.twofortyfouram.locale.intent.extra.BLURB"
    const val RESULT_CONDITION_SATISFIED = 16
    const val RESULT_CONDITION_UNSATISFIED = 17
    const val RESULT_CONDITION_UNKNOWN = 18

    const val B_KIND = "ytn.kind"
    const val B_SCREEN = "ytn.screen"
    const val B_QUICK = "ytn.quick"
    const val B_TARGET = "ytn.target"
    const val B_CONDITION = "ytn.condition"
    const val B_SIG = "ytn.sig"
    const val CONDITION = "CONDITION"

    enum class Condition(val label: String) { HEALTHY("NAS is healthy (no alerts)"), HAS_ALERTS("NAS has alerts or a warning"), OFFLINE("NAS can't be reached") }

    fun conditionResult(c: Condition, s: WidgetSnapshot): Int {
        if (s.serverName == null) return RESULT_CONDITION_UNKNOWN
        val offline = s.error != null
        val ok = !offline && s.alertCount == 0 && (s.poolHealth == Health.HEALTHY || s.poolHealth == Health.INFO)
        val yes = when (c) {
            Condition.OFFLINE -> offline
            Condition.HEALTHY -> ok
            Condition.HAS_ALERTS -> !offline && !ok
        }
        return if (yes) RESULT_CONDITION_SATISFIED else RESULT_CONDITION_UNSATISFIED
    }

    /** What's signed: every field the receiver acts on, in a fixed order. */
    fun bundleFields(kind: String, screen: String?, quick: String?, target: String?, condition: String?): String =
        listOf(kind, screen.orEmpty(), quick.orEmpty(), target.orEmpty(), condition.orEmpty()).joinToString("|")

    fun sign(context: Context, b: Bundle): Bundle = b.apply {
        putString(B_SIG, DeepLinkGuard.macFor(context, "plugin", bundleFields(
            getString(B_KIND).orEmpty(), getString(B_SCREEN), getString(B_QUICK), getString(B_TARGET), getString(B_CONDITION))))
    }

    fun verified(context: Context, b: Bundle?): Boolean {
        b ?: return false
        val sig = runCatching { b.getString(B_SIG) }.getOrNull() ?: return false
        val expected = DeepLinkGuard.macFor(context, "plugin", bundleFields(
            b.getString(B_KIND).orEmpty(), b.getString(B_SCREEN), b.getString(B_QUICK), b.getString(B_TARGET), b.getString(B_CONDITION)))
        return java.security.MessageDigest.isEqual(expected.toByteArray(), sig.toByteArray())
    }

    fun blurb(kind: String?, screen: String?, quick: String?, target: String?, condition: String?): String = when (kind) {
        REFRESH -> "Refresh NAS status"
        OPEN -> "Open ${SCREEN_LABELS[screen] ?: screen}"
        QUICK_ACTION -> (QuickAction.byId(quick)?.longLabel ?: "Quick action") + (target?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
        CONDITION -> runCatching { Condition.valueOf(condition.orEmpty()).label }.getOrDefault("NAS status")
        else -> "YTN"
    }
}

/** Permission-guarded broadcasts: refresh, and a status reply only permission holders can receive. */
class AutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Automation.enabled(context)) return
        when (intent.action) {
            Automation.action(context, Automation.REFRESH) -> Automation.refresh(context)
            Automation.action(context, Automation.QUERY_STATUS) -> {
                val extras = Automation.statusExtras(WidgetStore.load(context))
                if (isOrderedBroadcast) setResultExtras(extras)
                context.sendBroadcast(Intent(Automation.action(context, Automation.STATUS)).putExtras(extras), Automation.permission(context))
            }
        }
    }
}

/** Permission-guarded trampoline: opens a screen or a quick action's confirmation, then finishes. */
class AutomationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = intent
        val kind = i?.action?.substringAfterLast('.')
        val r = if (Automation.enabled(this)) Automation.parse(kind, i?.getStringExtra(Automation.EXTRA_SCREEN), i?.getStringExtra(Automation.EXTRA_QUICK_ACTION), i?.getStringExtra(Automation.EXTRA_TARGET)) else null
        when (r) {
            null -> Unit
            Automation.Request.Refresh -> Automation.refresh(this)
            else -> Automation.appIntent(this, r)?.let { runCatching { startActivity(it) } }
        }
        finish()
    }
}

/** Tasker / Locale plugin receiver: only bundles YTN signed, only while automation is on. */
class TaskerFireReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val b = runCatching { intent.getBundleExtra(Automation.LOCALE_EXTRA_BUNDLE) }.getOrNull()
        val on = Automation.enabled(context) && Automation.verified(context, b)
        when (intent.action) {
            Automation.LOCALE_QUERY_CONDITION -> {
                resultCode = if (!on) Automation.RESULT_CONDITION_UNKNOWN else {
                    val c = runCatching { Automation.Condition.valueOf(b!!.getString(Automation.B_CONDITION).orEmpty()) }.getOrNull()
                    c?.let { Automation.conditionResult(it, WidgetStore.load(context)) } ?: Automation.RESULT_CONDITION_UNKNOWN
                }
            }
            Automation.LOCALE_FIRE_SETTING -> {
                if (!on) return
                val r = Automation.parse(b!!.getString(Automation.B_KIND), b.getString(Automation.B_SCREEN), b.getString(Automation.B_QUICK), b.getString(Automation.B_TARGET)) ?: return
                when (r) {
                    Automation.Request.Refresh -> Automation.refresh(context)
                    else -> Automation.appIntent(context, r)?.let { target ->
                        (context.applicationContext as TrueNasApp).container.notifier.postAutomationPrompt(
                            Automation.blurb(b.getString(Automation.B_KIND), b.getString(Automation.B_SCREEN), b.getString(Automation.B_QUICK), b.getString(Automation.B_TARGET), null),
                            target,
                        )
                    }
                }
            }
        }
    }
}
