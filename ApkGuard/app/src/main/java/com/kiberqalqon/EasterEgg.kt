/*
 *  #### #  # #### #  #    #  # #### #  #
 *  #    #  # #    # #     #  # #  # #  #
 *  ###  #  # #    ##      #### #  # #  #
 *  #    #  # #    # #       #  #  # #  #
 *  #    #### #### #  #      #  #### ####
 *
 *  Kodni dekompilyatsiya qilyapsanmi? / Решил поковырять чужой код?
 *  Bu UzGuard. Muallifi — Muhammadali. Omad, "tadqiqotchi". :)
 */
package com.uzguard

import android.util.Log

/**
 * Dekompilyatorlar uchun "salom". Bu matn DEX ichida string konstanta sifatida qoladi —
 * APK'ni jadx bilan ochgan yoki `strings` bilan ko'rgan HAR KIM ko'radi. Oddiy foydalanuvchi
 * UI'da hech qachon ko'rmaydi (hech qaysi ekranga chiqarilmaydi). Sof prikol / mualliflik izi.
 */
object EasterEgg {

    val ART: String = """
        ╔══════════════════════════════════════════════════════════╗

          #### #  # #### #  #    #  # #### #  #
          #    #  # #    # #     #  # #  # #  #
          ###  #  # #    ##      #### #  # #  #
          #    #  # #    # #       #  #  # #  #
          #    #### #### #  #      #  #### ####

          FUCK YOU. Kodni dekompilyatsiya qilyapsanmi?
          Bu UzGuard. Muallifi — Muhammadali.
          Omad, "tadqiqotchi". Vaqtingni behuda sarflama. :)

        ╚══════════════════════════════════════════════════════════╝
    """.trimIndent()

    /** App ishga tushganda logcat'ga bir marta bosib qo'yamiz (DEX'da saqlanishini kafolatlaydi). */
    fun stamp() {
        Log.i("UzGuard", ART)
    }
}
