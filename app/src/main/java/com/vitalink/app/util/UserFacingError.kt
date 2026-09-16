package com.vitalink.app.util

import android.util.Log
import com.vitalink.app.BuildConfig
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import retrofit2.HttpException

/** Patient-facing errors never include response bodies, URLs, SQL or exception messages. */
object UserFacingError {
    enum class Action { LOAD, SAVE, DELETE, SYNC, SIGN_IN, REGISTER, CHANGE_PASSWORD, GENERAL }

    fun http(status: Int, action: Action = Action.GENERAL): String {
        if (BuildConfig.DEBUG) Log.w("MyHFGuardRequest", "$action failed; HTTP $status")
        val reason = when (status) {
            401 -> AppLanguage.text(
                "Please sign in again and retry.", "Sila log masuk semula dan cuba lagi.",
                "请重新登录后重试。", "மீண்டும் உள்நுழைந்து முயற்சிக்கவும்."
            )
            403 -> AppLanguage.text(
                "Your account cannot perform this action. Please contact your administrator.",
                "Akaun anda tidak dibenarkan melakukan tindakan ini. Sila hubungi pentadbir.",
                "您的账户无权执行此操作，请联系管理员。",
                "உங்கள் கணக்கில் இந்தச் செயலைச் செய்ய அனுமதி இல்லை. நிர்வாகியைத் தொடர்புகொள்ளவும்."
            )
            404 -> AppLanguage.text(
                "The requested information or service is unavailable. Please retry or contact your administrator.",
                "Maklumat atau perkhidmatan yang diminta tidak tersedia. Cuba lagi atau hubungi pentadbir.",
                "所需的信息或服务不可用，请重试或联系管理员。",
                "கோரிய தகவல் அல்லது சேவை கிடைக்கவில்லை. மீண்டும் முயலவும் அல்லது நிர்வாகியைத் தொடர்புகொள்ளவும்."
            )
            400, 422 -> AppLanguage.text(
                "The request could not be accepted. Check your details and try again. If it continues, contact your administrator.",
                "Permintaan tidak dapat diterima. Semak maklumat dan cuba lagi. Jika berterusan, hubungi pentadbir.",
                "请求未被接受，请检查填写的信息后重试。如果仍有问题，请联系管理员。",
                "கோரிக்கையை ஏற்க முடியவில்லை. விவரங்களைச் சரிபார்த்து மீண்டும் முயலவும். தொடர்ந்தால் நிர்வாகியைத் தொடர்புகொள்ளவும்."
            )
            409 -> AppLanguage.text(
                "This information may already exist or have changed. Refresh the page and check before trying again.",
                "Maklumat ini mungkin sudah wujud atau telah berubah. Muat semula halaman dan semak sebelum mencuba lagi.",
                "此信息可能已存在或已更改，请刷新页面并核对后再试。",
                "இந்தத் தகவல் ஏற்கனவே இருக்கலாம் அல்லது மாறியிருக்கலாம். பக்கத்தைப் புதுப்பித்துச் சரிபார்த்து மீண்டும் முயலவும்."
            )
            408, 504 -> timeoutReason()
            429 -> AppLanguage.text(
                "Too many attempts. Please wait a moment and try again.",
                "Terlalu banyak percubaan. Sila tunggu sebentar dan cuba lagi.",
                "尝试次数过多，请稍后再试。", "அதிகமான முயற்சிகள். சிறிது நேரம் காத்திருந்து மீண்டும் முயற்சிக்கவும்."
            )
            in 500..599 -> AppLanguage.text(
                "The service is temporarily unavailable. Please try again later.",
                "Perkhidmatan tidak tersedia buat sementara waktu. Sila cuba lagi kemudian.",
                "服务暂时不可用，请稍后重试。", "சேவை தற்காலிகமாகக் கிடைக்கவில்லை. பின்னர் மீண்டும் முயற்சிக்கவும்."
            )
            else -> retryReason()
        }
        return "${prefix(action)} $reason"
    }

    fun from(error: Throwable, action: Action = Action.GENERAL): String {
        // Cancellation is control flow, not a patient-facing failure.
        if (error is CancellationException && error !is TimeoutCancellationException) throw error
        if (error is HttpException) return http(error.code(), action)
        // Do not log raw bodies, tokens, credentials or patient data.
        if (BuildConfig.DEBUG) Log.w("MyHFGuardRequest", "$action failed; ${error.javaClass.simpleName}")
        val reason = when (error) {
            is SocketTimeoutException, is TimeoutCancellationException -> timeoutReason()
            is IOException -> connectionReason()
            else -> retryReason()
        }
        return "${prefix(action)} $reason"
    }

    fun connection(action: Action = Action.GENERAL): String = "${prefix(action)} ${connectionReason()}"
    fun generic(action: Action = Action.GENERAL): String = "${prefix(action)} ${retryReason()}"

    private fun prefix(action: Action): String = when (action) {
        Action.LOAD -> AppLanguage.text("Unable to load your information.", "Tidak dapat memuatkan maklumat anda.", "无法加载您的信息。", "உங்கள் தகவலை ஏற்ற முடியவில்லை.")
        Action.SAVE -> AppLanguage.text("Your changes could not be confirmed as saved.", "Simpanan perubahan anda tidak dapat disahkan.", "无法确认您的更改已保存。", "உங்கள் மாற்றங்கள் சேமிக்கப்பட்டதை உறுதிப்படுத்த முடியவில்லை.")
        Action.DELETE -> AppLanguage.text("Deletion could not be confirmed.", "Pemadaman tidak dapat disahkan.", "无法确认删除是否成功。", "நீக்கப்பட்டதை உறுதிப்படுத்த முடியவில்லை.")
        Action.SYNC -> AppLanguage.text("Sync could not be completed.", "Penyegerakan tidak dapat diselesaikan.", "无法完成同步。", "ஒத்திசைவை முடிக்க முடியவில்லை.")
        Action.SIGN_IN -> AppLanguage.text("Unable to sign in.", "Tidak dapat log masuk.", "无法登录。", "உள்நுழைய முடியவில்லை.")
        Action.REGISTER -> AppLanguage.text("Account registration could not be completed.", "Pendaftaran akaun tidak dapat diselesaikan.", "无法完成账户注册。", "கணக்குப் பதிவை முடிக்க முடியவில்லை.")
        Action.CHANGE_PASSWORD -> AppLanguage.text("The password change could not be confirmed.", "Penukaran kata laluan tidak dapat disahkan.", "无法确认密码是否已更改。", "கடவுச்சொல் மாற்றத்தை உறுதிப்படுத்த முடியவில்லை.")
        Action.GENERAL -> AppLanguage.text("The action could not be completed.", "Tindakan tidak dapat diselesaikan.", "无法完成操作。", "செயலை முடிக்க முடியவில்லை.")
    }

    private fun connectionReason() = AppLanguage.text(
        "Check your Wi-Fi or mobile data connection and try again.",
        "Semak sambungan Wi-Fi atau data mudah alih anda dan cuba lagi.",
        "请检查 Wi-Fi 或移动数据连接后重试。", "Wi-Fi அல்லது மொபைல் தரவு இணைப்பைச் சரிபார்த்து மீண்டும் முயற்சிக்கவும்."
    )

    private fun timeoutReason() = AppLanguage.text(
        "The connection took too long. Check your connection and refresh to confirm the latest information before trying again.",
        "Sambungan mengambil masa terlalu lama. Semak sambungan dan muat semula untuk mengesahkan maklumat terkini sebelum mencuba lagi.",
        "连接超时，请检查网络并刷新以确认最新信息后再试。",
        "இணைப்புக்கு அதிக நேரம் ஆனது. இணைப்பைச் சரிபார்த்து, மீண்டும் முயல்வதற்கு முன் புதுப்பித்து சமீபத்திய தகவலை உறுதிசெய்யவும்."
    )

    private fun retryReason() = AppLanguage.text(
        "Please refresh and try again. If the problem continues, contact your administrator.",
        "Sila muat semula dan cuba lagi. Jika masalah berterusan, hubungi pentadbir.",
        "请刷新后重试。如果问题仍然存在，请联系管理员。",
        "புதுப்பித்து மீண்டும் முயற்சிக்கவும். சிக்கல் தொடர்ந்தால் நிர்வாகியைத் தொடர்புகொள்ளவும்."
    )
}
