package com.uzguard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.uzguard.databinding.ActivityOnboardingBinding
import kotlin.math.abs

/**
 * Onboarding — v4 «Milliy Kiber Himoya» dizayni (design_v4_extracted/screens1.jsx → ONB).
 *
 * 3 slayd: har birida 176dp doira ichida slayd ikonkasi (ic4_scan / ic4_shield_alert /
 * ic4_check_circle), eyebrow "1 · 3", sarlavha va matn. Pastdagi tugma oxirgi slaydgacha
 * "Davom etish", oxirgisida "Himoyani yoqish" — bosilganda onboarding tugaydi.
 *
 * O'tishlar saqlangan: tugatish → InitialScanActivity (agar hali o'tkazilmagan bo'lsa),
 * aks holda DashboardNewActivity. "O'tkazib yuborish" ham xuddi shu yo'l.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.apply(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeHelper.applyAccent(this)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val pages = listOf(
            Page(R.drawable.ic4_scan, R.string.kq4_onb_title_1, R.string.kq4_onb_body_1),
            Page(R.drawable.ic4_shield_alert, R.string.kq4_onb_title_2, R.string.kq4_onb_body_2),
            Page(R.drawable.ic4_check_circle, R.string.kq4_onb_title_3, R.string.kq4_onb_body_3),
        )

        binding.viewPager.adapter = PagerAdapter(pages)

        // Yumshoq sahifa o'tishi: joriy slayd to'liq, qo'shnilar biroz kichrayadi va xira tortadi.
        binding.viewPager.setPageTransformer { page, position ->
            val abs = abs(position)
            if (position < -1f || position > 1f) {
                page.alpha = 0f
            } else {
                page.alpha = 0.4f + (1f - abs) * 0.6f
                val scale = 0.92f + (1f - abs) * 0.08f
                page.scaleX = scale
                page.scaleY = scale
            }
        }

        buildIndicator(pages.size)

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateIndicator(position)
                binding.btnNext.setText(
                    if (position == pages.lastIndex) R.string.kq4_onb_enable else R.string.kq4_continue
                )
            }
        })

        binding.btnNext.setOnClickListener {
            val current = binding.viewPager.currentItem
            if (current < pages.lastIndex) {
                binding.viewPager.currentItem = current + 1
            } else {
                finishOnboarding()
            }
        }
        binding.btnSkip.setOnClickListener { finishOnboarding() }
    }

    private fun finishOnboarding() {
        Config.setFirstRunComplete(this)
        // Posle Onboarding zapuskaem pervichnyy poiskj opasnykh APK na ustroystve —
        // chtoby user srazu uvidel chto UzGuard real'no rabotayet i chto na ego
        // telefone est' opasnogo. Posle InitialScan flag stavitsya, bol'she ne pokazyvayetsya.
        val target = if (!Config.isInitialScanDone(this)) {
            InitialScanActivity::class.java
        } else {
            DashboardNewActivity::class.java
        }
        startActivity(Intent(this, target))
        finish()
    }

    private fun buildIndicator(count: Int) {
        binding.layoutIndicator.removeAllViews()
        for (i in 0 until count) {
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                    if (i > 0) marginStart = dp(7)
                }
                setBackgroundResource(R.drawable.kq_pager_dot_off)
            }
            binding.layoutIndicator.addView(dot)
        }
        updateIndicator(0)
    }

    // v4 dizayn: faol nuqta — 26x8dp pilyulya (kq_primary), qolganlari 8dp doira
    // (kq_hairline_strong). screens1.jsx Onboarding'dagi nuqta-progressga mos.
    private fun updateIndicator(active: Int) {
        for (i in 0 until binding.layoutIndicator.childCount) {
            val dot = binding.layoutIndicator.getChildAt(i)
            val lp = dot.layoutParams as LinearLayout.LayoutParams
            val on = i == active
            lp.width = if (on) dp(26) else dp(8)
            lp.height = dp(8)
            dot.layoutParams = lp
            dot.setBackgroundResource(
                if (on) R.drawable.kq_pager_dot_on else R.drawable.kq_pager_dot_off
            )
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private data class Page(
        val iconRes: Int,
        val titleRes: Int,
        val descRes: Int,
    )

    private class PagerAdapter(private val pages: List<Page>) :
        RecyclerView.Adapter<PagerAdapter.PageViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_onboarding_page, parent, false)
            return PageViewHolder(view)
        }

        override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
            val p = pages[position]
            holder.icon.setImageResource(p.iconRes)
            holder.eyebrow.text = holder.itemView.context
                .getString(R.string.kq4_onb_step, position + 1, pages.size)
            holder.title.setText(p.titleRes)
            holder.desc.setText(p.descRes)
        }

        override fun getItemCount(): Int = pages.size

        class PageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.ivSlideIcon)
            val eyebrow: TextView = view.findViewById(R.id.tvEyebrow)
            val title: TextView = view.findViewById(R.id.tvTitle)
            val desc: TextView = view.findViewById(R.id.tvDesc)
        }
    }
}
