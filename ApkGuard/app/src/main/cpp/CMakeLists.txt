# ============================================================
# UzGuard — native himoya kutubxonasi (libkqguard.so).
#
# Vazifasi: eng oson "patch qilinadigan" Kotlin tekshiruvlarini (imzo, anti-debug)
# nativega ko'chirish. .so'ni reverse qilish DEX'dan ancha qiyin, shuning uchun
# buzg'unchi o'z-o'zini himoyani (self-kill) o'chirib qo'yishi qiyinlashadi.
#
# DIQQAT: bu yerda HECH QANDAY maxfiy sir yo'q. Tarmoq sirlari Shield orqali
# BuildConfig'da (Secrets.kt). Bu yerdagi KQ_EXPECTED_SIG — release imzo
# sertifikat(lar)ining SHA-256'i, VERGUL bilan ajratilgan to'plam bo'lishi mumkin
# (SD-01: sideload kalit + Play App Signing serti). OCHIQ qiymat (APK'dan baribir
# hisoblab olsa bo'ladi); build vaqtida -D orqali beriladi, qo'lda literal emas.
# ============================================================
cmake_minimum_required(VERSION 3.22.1)
project(kqguard C)

if(NOT DEFINED KQ_EXPECTED_SIG)
  set(KQ_EXPECTED_SIG "")
endif()

# Build vaqtida ushlaymiz: bo'sh imzo-xeshi bilan qurilsa, native nSigInvalid har doim
# "noto'g'ri" qaytaradi — runtime'da jim self-kill xavfi (Kotlin fallback bo'lsa-da).
# build.gradle.kts har doim -DKQ_EXPECTED_SIG=... beradi; bu bo'sh bo'lsa konfiguratsiya xatosi.
if(KQ_EXPECTED_SIG STREQUAL "")
  message(FATAL_ERROR "KQ_EXPECTED_SIG bo'sh! -DKQ_EXPECTED_SIG=<sha256> bilan bering (app/build.gradle.kts externalNativeBuild).")
endif()

add_library(kqguard SHARED kqguard.c)

target_compile_definitions(kqguard PRIVATE KQ_EXPECTED_SIG="${KQ_EXPECTED_SIG}")

# Optimallashtirish + ramz jadvalini olib tashlash (strings/symbols kamroq zacepka).
target_compile_options(kqguard PRIVATE -O2 -fvisibility=hidden -fomit-frame-pointer)
target_link_options(kqguard PRIVATE -Wl,--strip-all -Wl,-z,noexecstack)

find_library(log-lib log)
target_link_libraries(kqguard ${log-lib})
