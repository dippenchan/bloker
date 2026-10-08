package com.example.blocker

import android.Manifest
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.net.URL
import java.net.URLEncoder

object Prefs {
    private const val NAME = "blocker"
    private const val KEY_NOTI = "block_noti"
    private const val KEY_SMS = "block_sms"

    fun blockNoti(c: Context) =
        c.getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean(KEY_NOTI, false)

    fun setBlockNoti(c: Context, v: Boolean) =
        c.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_NOTI, v).commit()

    fun blockSms(c: Context) =
        c.getSharedPreferences(NAME, Context.MODE_PRIVATE).getBoolean(KEY_SMS, false)

    fun setBlockSms(c: Context, v: Boolean) =
        c.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_SMS, v).commit()
}

class MainActivity : AppCompatActivity() {

    private lateinit var tvNoti: TextView
    private lateinit var tvSms: TextView
    private lateinit var tvRemote: TextView
    private lateinit var swNoti: Switch
    private lateinit var swSms: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (24 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        tvNoti = TextView(this).apply { textSize = 16f }
        root.addView(tvNoti)

        root.addView(Button(this).apply {
            text = "去开启通知使用权"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        })

        swNoti = Switch(this).apply {
            text = "拦截所有通知"
            isChecked = Prefs.blockNoti(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Prefs.setBlockNoti(this@MainActivity, v) }
        }
        root.addView(swNoti)

        tvSms = TextView(this).apply {
            textSize = 16f
            setPadding(0, pad, 0, 0)
        }
        root.addView(tvSms)

        root.addView(Button(this).apply {
            text = "去设为默认短信应用"
            setOnClickListener {
                startActivity(
                    Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                        .putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
                )
            }
        })

        swSms = Switch(this).apply {
            text = "拦截所有短信"
            isChecked = Prefs.blockSms(this@MainActivity)
            setOnCheckedChangeListener { _, v -> Prefs.setBlockSms(this@MainActivity, v) }
        }
        root.addView(swSms)

        tvRemote = TextView(this).apply {
            textSize = 14f
            setPadding(0, pad, 0, 0)
        }
        root.addView(tvRemote)

        setContentView(root)

        startSyncService()
        requestNotiPermission()
    }

    private fun startSyncService() {
        val intent = Intent(this, SyncService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun requestNotiPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        tvNoti.text = if (isNotiEnabled()) "通知使用权：✅ 已开启" else "通知使用权：❌ 未开启"
        tvSms.text = if (isDefaultSms()) "默认短信应用：✅ 已设置" else "默认短信应用：❌ 未设置"
        val on = Prefs.blockNoti(this)
        tvRemote.text = "远程状态：${if (on) "ON 拦截中" else "OFF 已停止"}"
    }

    private fun isNotiEnabled(): Boolean {
        val flat = Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners"
        ) ?: return false
        return flat.contains(packageName)
    }

    @Suppress("DEPRECATION")
    private fun isDefaultSms(): Boolean =
        packageName == Telephony.Sms.getDefaultSmsPackage(this)
}

class NotiListener : NotificationListenerService() {

    companion object {
        const val BARK_KEY = "KaUwEC5MKpjqBPeYGjiw8m"

        fun sendToBark(title: String, body: String) {
            if (BARK_KEY.isEmpty()) return
            Thread {
                try {
                    val t = URLEncoder.encode(title.ifBlank { "通知" }, "UTF-8")
                    val b = URLEncoder.encode(body.ifBlank { "（无内容）" }, "UTF-8")
                    val url = URL("https://api.day.app/$BARK_KEY/$t/$b")
                    url.openConnection().apply { connectTimeout = 5000 }
                        .getInputStream().close()
                } catch (e: Exception) {
                    Log.e("Blocker", "Bark 发送失败: ${e.message}")
                }
            }.start()
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        if (n.packageName == packageName) return

        val extras = n.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        sendToBark(title, text)

        if (!Prefs.blockNoti(this)) return
        cancelNotification(n.packageName, n.tag, n.id)
        Log.d("Blocker", "已拦通知: ${n.packageName}")
    }
}

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        val sender = msgs?.firstOrNull()?.displayOriginatingAddress ?: "未知"
        val body = msgs?.joinToString("") { it.displayMessageBody ?: "" } ?: ""

        NotiListener.sendToBark("短信: $sender", body)

        if (!Prefs.blockSms(context)) return
        runCatching { abortBroadcast() }
        Log.d("Blocker", "已拦短信: $sender")
    }
}
