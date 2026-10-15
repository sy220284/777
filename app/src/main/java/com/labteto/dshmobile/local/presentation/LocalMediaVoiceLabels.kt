package com.labteto.dshmobile.local.presentation

import com.labteto.dshmobile.local.model.LocalMediaOffer
import com.labteto.dshmobile.local.model.LocalMediaProvider

/** Technical voice IDs remain unchanged for provider calls; all public labels are Chinese. */
internal object LocalMediaVoiceLabels {
    private val miniMax = mapOf(
        "male-qn-qingse" to "青涩青年", "male-qn-badao" to "霸道青年",
        "male-qn-daxuesheng" to "青年大学生", "male-qn-jingying" to "精英青年",
        "female-shaonv" to "少女", "female-yujie" to "御姐",
        "female-chengshu" to "成熟女性", "female-tianmei" to "甜美女性",
        "clever_boy" to "聪明男童", "cute_boy" to "可爱男童",
        "lovely_girl" to "萌萌女童", "cartoon_pig" to "卡通猪小琪",
        "bingjiao_didi" to "病娇弟弟", "junlang_nanyou" to "俊朗男友",
        "chunzhen_xuedi" to "纯真学弟", "lengdan_xiongzhang" to "冷淡学长",
        "badao_shaoye" to "霸道少爷", "tianxin_xiaoling" to "甜心小玲",
        "qiaopi_mengmei" to "俏皮萌妹", "wumei_yujie" to "妩媚御姐",
        "diadia_xuemei" to "嗲嗲学妹", "danya_xuejie" to "淡雅学姐",
        "Arrogant_Miss" to "嚣张小姐", "Robot_Armor" to "机械战甲",
        "Chinese (Mandarin)_Gentleman" to "温润男声",
        "Chinese (Mandarin)_Warm_Girl" to "温暖少女",
        "Chinese (Mandarin)_Soft_Girl" to "柔和少女",
        "Chinese (Mandarin)_Crisp_Girl" to "清脆少女",
        "Chinese (Mandarin)_Gentle_Senior" to "温柔学姐",
        "Chinese (Mandarin)_Mature_Woman" to "傲娇御姐",
        "Chinese (Mandarin)_Male_Announcer" to "播报男声",
        "Chinese (Mandarin)_News_Anchor" to "新闻女声",
        "Chinese (Mandarin)_Sweet_Lady" to "甜美女声"
    )
    private val openAI = listOf(
        "alloy", "ash", "ballad", "coral", "echo", "fable", "nova",
        "onyx", "sage", "shimmer", "verse", "marin", "cedar",
    ).mapIndexed { i, id -> id to "标准音色${i + 1}" }.toMap()

    fun label(provider: LocalMediaProvider, id: String): String =
        (if (provider == LocalMediaProvider.MINIMAX) miniMax else openAI)[id] ?: "自定义音色"

    fun localize(offer: LocalMediaOffer, voices: List<LocalMediaVoice>): List<LocalMediaVoice> =
        voices.mapIndexed { index, voice ->
            val preset = (if (offer.provider == LocalMediaProvider.MINIMAX) miniMax else openAI)[voice.id]
            val official = voice.name.trim().takeIf { name -> name.isNotEmpty() && name.none { it in 'a'..'z' || it in 'A'..'Z' } }
            voice.copy(name = preset ?: official ?: "系统音色${index + 1}")
        }

    /** Unknown persona voice gender is not guessed. */
    fun suggestedId(provider: LocalMediaProvider, voiceStyle: String): String =
        when (provider) {
            LocalMediaProvider.OPENAI -> "alloy"
            LocalMediaProvider.MINIMAX -> when {
                listOf("女声", "女性", "少女", "甜美", "柔美", "清脆").any(voiceStyle::contains) ->
                    "female-shaonv"
                listOf("男声", "男性", "少年", "男青年", "男子").any(voiceStyle::contains) ->
                    "male-qn-qingse"
                else -> ""
            }
            else -> ""
        }
}
