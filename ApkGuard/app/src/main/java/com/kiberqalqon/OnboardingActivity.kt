package com.kiberqalqon

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.kiberqalqon.databinding.ActivityOnboardingBinding
import kotlin.math.abs

/**
 * Onboarding (design from screens-onboarding.jsx — 3 slides).
 *
 * Each slide carries an eyebrow ("01 · BOSHLASH"), a title, a body, and a
 * visual kind (METER / THREATS / ALERT) that picks which of the three
 * mocked-up visuals to show in the page layout.
 *
 * Footer button cycles "Davom etish" until the last slide, where it switches
 * to "Boshlash · Himoyani yoqish" and finishes onboarding.
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
            Page(R.string.onb_eyebrow_1, R.string.onboarding_title_1, R.string.onboarding_desc_1, Visual.METER),
            Page(R.string.onb_eyebrow_2, R.string.onboarding_title_2, R.string.onboarding_desc_2, Visual.THREATS),
            Page(R.string.onb_eyebrow_3, R.string.onboarding_title_3, R.string.onboarding_desc_3, Visual.ALERT),
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
                    if (position == pages.lastIndex) R.string.btn_start else R.string.btn_next
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
        // chtoby user srazu uvidel chto KiberQalqon real'no rabotayet i chto na ego
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
                    if (i > 0) marginStart = dp(6)
                }
                setBackgroundResource(R.drawable.kq_pager_dot_off)
            }
            binding.layoutIndicator.addView(dot)
        }
        updateIndicator(0)
    }

    // Active dot is a 22dp × 8dp pill in primary; inactive ones are 8dp circles
    // in hairline color. Mirrors `.pager-dots span` / `.pager-dots span.on` in styles.css.
    private fun updateIndicator(active: Int) {
        for (i in 0 until binding.layoutIndicator.childCount) {
            val dot = binding.layoutIndicator.getChildAt(i)
            val lp = dot.layoutParams as LinearLayout.LayoutParams
            val on = i == active
            lp.width = if (on) dp(22) else dp(8)
            lp.height = dp(8)
            dot.layoutParams = lp
            dot.setBackgroundResource(
                if (on) R.drawable.kq_pager_dot_on else R.drawable.kq_pager_dot_off
            )
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private enum class Visual { METER, THREATS, ALERT }

    private data class Page(
        val eyebrowRes: Int,
        val titleRes: Int,
        val descRes: Int,
        val visual: Visual,
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
            holder.eyebrow.setText(p.eyebrowRes)
            holder.title.setText(p.titleRes)
            holder.desc.setText(p.descRes)
            // Show only the visual that matches this slide.
            holder.meter.visibility = if (p.visual == Visual.METER) View.VISIBLE else View.GONE
            holder.threats.visibility = if (p.visual == Visual.THREATS) View.VISIBLE else View.GONE
            holder.alert.visibility = if (p.visual == Visual.ALERT) View.VISIBLE else View.GONE
        }

        override fun getItemCount(): Int = pages.size

        class PageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val eyebrow: TextView = view.findViewById(R.id.tvEyebrow)
            val title: TextView = view.findViewById(R.id.tvTitle)
            val desc: TextView = view.findViewById(R.id.tvDesc)
            val meter: View = view.findViewById(R.id.visualMeter)
            val threats: View = view.findViewById(R.id.visualThreats)
            val alert: View = view.findViewById(R.id.visualAlert)
        }
    }
}
