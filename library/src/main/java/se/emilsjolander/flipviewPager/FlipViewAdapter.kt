package se.emilsjolander.flipviewPager

import android.view.View
import androidx.viewpager.widget.PagerAdapter

abstract class FlipViewAdapter() : PagerAdapter() {
    override fun isViewFromObject(view: View, `object`: Any): Boolean {
        return false
    }

}