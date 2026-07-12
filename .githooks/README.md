# .githooks — mahalliy sifat-gejtlari

`pre-push` — push'dan OLDIN kompilyatsiyani offline tekshiradi (sekin CI'ni kutmasdan):
1. **Cloud TypeScript** (`tsc`) — bloklovchi (mahalliy ishonchli ishlaydi).
2. **Android Kotlin compile** (`:app:compileDebugKotlin`, dex/R8/NDK'siz — yengil) — bloklovchi.

## Yoqish (bir marta, har klon uchun)
```sh
git config core.hooksPath .githooks
```

## O'tkazish (kerak bo'lsa)
- Android compile RAM yetmay yiqilsa:  `export KQ_SKIP_ANDROID=1`
- Butun hook'ni bir martaga o'tkazish:   `git push --no-verify`

Nega kerak: mahalliy to'liq `assembleDebug` RAM yetmasligidan yiqiladi (loyiha qoidasi),
shu sabab "Val cannot be reassigned" kabi oddiy kompilyatsiya xatosi faqat push+CI (2.5 daq)
dan keyin ko'rinardi. Bu hook uni push'dan oldin ilib oladi. CI'da ham parallel `quick` job
(compile + detekt) shuni ~1 daqiqada qizil qiladi.
